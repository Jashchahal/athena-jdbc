package io.burt.athena.result.s3;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class InputStreamResponseTransformer extends InputStream implements AsyncResponseTransformer<GetObjectResponse, InputStream>, Subscriber<ByteBuffer> {
    private static final ByteBuffer END_MARKER = ByteBuffer.allocate(0);
    private static final int TARGET_BUFFER_SIZE = 1 << 25;
    private static final int CHUNKS_REQUEST_LIMIT = 1000;
    private static final float CHUNK_SIZE_EXPONENTIAL_WEIGHT = 0.2f;
    private static final float CHUNK_SIZE_INITIAL_ESTIMATE = 8192f;

    private final CompletableFuture<InputStream> future;
    private final BlockingQueue<ByteBuffer> chunks;

    private GetObjectResponse response;
    private Subscription subscription;
    private ByteBuffer readChunk;
    private Throwable error;
    private AtomicBoolean complete;
    private AtomicInteger approximateBufferSize;
    private AtomicInteger requests;
    private volatile float approximateChunkSize;

    public InputStreamResponseTransformer() {
        this.future = new CompletableFuture<>();
        this.chunks = new LinkedBlockingQueue<>();
        this.complete = new AtomicBoolean(false);
        this.approximateBufferSize = new AtomicInteger(0);
        this.requests = new AtomicInteger(0);
        this.approximateChunkSize = CHUNK_SIZE_INITIAL_ESTIMATE;
    }

    @Override
    public CompletableFuture<InputStream> prepare() {
        return future;
    }

    @Override
    public void onResponse(GetObjectResponse r) {
        response = r;
        future.complete(this);
    }

    @Override
    public void onStream(SdkPublisher<ByteBuffer> publisher) {
        publisher.subscribe(this);
    }

    @Override
    public void exceptionOccurred(Throwable t) {
        error = t;
        future.completeExceptionally(t);
        try {
            close();
        } catch (Exception e) { }
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        if (response.contentLength() < TARGET_BUFFER_SIZE) {
            requests.set(Integer.MAX_VALUE);
            subscription.request(Long.MAX_VALUE);
        } else {
            requests.set(10);
            subscription.request(10L);
        }
    }

    @Override
    public void onNext(ByteBuffer byteBuffer) {
        int chunkSize = byteBuffer.remaining();
        if (chunkSize > 0) {
            chunks.offer(byteBuffer);
            approximateChunkSize += CHUNK_SIZE_EXPONENTIAL_WEIGHT * (chunkSize - approximateChunkSize);
        }
        requests.decrementAndGet();
        int size = approximateBufferSize.addAndGet(chunkSize);
        maybeRequestMore(size);
    }

    private void maybeRequestMore(int currentSize) {
        if (currentSize < TARGET_BUFFER_SIZE) {
            int newRequests = requests.get() + 10;
            if (newRequests < CHUNKS_REQUEST_LIMIT) {
                if (newRequests * approximateChunkSize + currentSize < TARGET_BUFFER_SIZE) {
                    requests.addAndGet(10);
                    subscription.request(10);
                }
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        exceptionOccurred(t);
    }

    @Override
    public void onComplete() {
        chunks.offer(END_MARKER);
        complete.set(true);
    }

    @Override
    public int available() throws IOException {
        if (error != null) {
            throw new IOException(error);
        }
        if (readChunk != null) {
            return readChunk.remaining();
        } else {
            return 0;
        }
    }

    private boolean ensureChunk() throws IOException {
        if (error != null) {
            throw new IOException(error);
        }
        if (readChunk == END_MARKER) {
            return false;
        } else if (readChunk == null || !readChunk.hasRemaining()) {
            try {
                readChunk = chunks.take();
                if (readChunk == END_MARKER) {
                    return false;
                } else {
                    int size = approximateBufferSize.addAndGet(-readChunk.remaining());
                    maybeRequestMore(size);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        return true;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        if (ensureChunk()) {
            int actualLength = Math.min(length, readChunk.remaining());
            readChunk.get(destination, offset, actualLength);
            return actualLength;
        } else {
            return -1;
        }
    }

    @Override
    public int read() throws IOException {
        if (ensureChunk()) {
            return Byte.toUnsignedInt(readChunk.get());
        } else {
            return -1;
        }
    }

    @Override
    public void close() throws IOException {
        if (!complete.get()) {
            chunks.clear();
            chunks.offer(END_MARKER);
            subscription.cancel();
            future.cancel(true);
        }
        super.close();
    }
}
