package io.burt.athena.result;

import io.burt.athena.AthenaResultSetMetaData;
import software.amazon.awssdk.services.athena.AthenaAsyncClient;
import software.amazon.awssdk.services.athena.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.athena.model.QueryExecution;
import software.amazon.awssdk.services.athena.model.Row;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class StandardResult implements Result {
    public static final int MAX_FETCH_SIZE = 1000;

    private int fetchSize;

    protected final QueryExecution queryExecution;
    protected final AthenaAsyncClient athenaClient;
    protected final Duration timeout;

    protected Iterator<Row> currentRows;
    protected Row currentRow;

    private AthenaResultSetMetaData resultSetMetaData;
    private String nextToken;
    private int rowNumber;

    public StandardResult(AthenaAsyncClient athenaClient, QueryExecution queryExecution, int fetchSize, Duration timeout) {
        this.athenaClient = athenaClient;
        this.queryExecution = queryExecution;
        this.fetchSize = fetchSize;
        this.timeout = timeout;
        this.rowNumber = 0;
        this.nextToken = null;
        this.currentRows = null;
        this.currentRow = null;
        this.resultSetMetaData = null;
    }

    private void ensureResults() throws SQLException, InterruptedException {
        if (shouldLoadNextPage()) {
            try {
                GetQueryResultsResponse response = loadNextPage();
                nextToken = response.nextToken();
                resultSetMetaData = new AthenaResultSetMetaData(queryExecution, response.resultSet().resultSetMetadata());
                currentRows = response.resultSet().rows().iterator();
                if (rowNumber == 0 && currentRows.hasNext()) {
                    currentRows.next();
                }
            } catch (TimeoutException ie) {
                throw new SQLTimeoutException(ie);
            } catch (ExecutionException ee) {
                SQLException eee = new SQLException(ee.getCause());
                eee.addSuppressed(ee);
                throw eee;
            }
        }
    }

    protected boolean shouldLoadNextPage() throws SQLException {
        return (getRowNumber() == 0 && currentRows == null) || (nextToken != null && !currentRows.hasNext());
    }

    protected GetQueryResultsResponse loadNextPage() throws InterruptedException, TimeoutException, ExecutionException {
        return loadPage().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    protected CompletableFuture<GetQueryResultsResponse> loadPage() {
        return loadPage(nextToken);
    }

    protected CompletableFuture<GetQueryResultsResponse> loadPage(String nextToken) {
        return athenaClient.getQueryResults(builder -> {
            builder.nextToken(nextToken);
            builder.queryExecutionId(queryExecution.queryExecutionId());
            builder.maxResults(fetchSize);
        });
    }

    @Override
    public int getFetchSize() throws SQLException {
        return fetchSize;
    }

    @Override
    public void setFetchSize(int newFetchSize) throws SQLException {
        if (newFetchSize > MAX_FETCH_SIZE) {
            throw new SQLException(String.format("Fetch size too large (got %d, max is %d)", newFetchSize, MAX_FETCH_SIZE));
        } else {
            fetchSize = newFetchSize;
        }
    }

    @Override
    public AthenaResultSetMetaData getMetaData() throws SQLException {
        if (resultSetMetaData == null) {
            try {
                ensureResults();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new SQLException(ie);
            }
        }
        return resultSetMetaData;
    }

    @Override
    public int getRowNumber() throws SQLException {
        return rowNumber;
    }

    @Override
    public boolean next() throws SQLException {
        try {
            ensureResults();
            rowNumber++;
            if (currentRows.hasNext()) {
                currentRow = currentRows.next();
            } else {
                currentRow = null;
            }
            return currentRow != null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SQLException(ie);
        }
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return currentRow.data().get(columnIndex - 1).varCharValue();
    }

    @Override
    public ResultPosition getPosition() throws SQLException {
        if (getRowNumber() == 0) {
            return ResultPosition.BEFORE_FIRST;
        } else if (getRowNumber() == 1) {
            return ResultPosition.FIRST;
        } else if (nextToken == null && currentRows != null && currentRow != null && !currentRows.hasNext()) {
            return ResultPosition.LAST;
        } else if (nextToken == null && currentRows != null && currentRow == null) {
            return ResultPosition.AFTER_LAST;
        } else {
            return ResultPosition.MIDDLE;
        }
    }

    @Override
    public void close() {
        currentRows = null;
        currentRow = null;
    }
}
