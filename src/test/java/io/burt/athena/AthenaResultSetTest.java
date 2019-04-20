package io.burt.athena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.ColumnInfo;
import software.amazon.awssdk.services.athena.model.GetQueryResultsRequest;
import software.amazon.awssdk.services.athena.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.athena.model.Row;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AthenaResultSetTest {
    private AthenaResultSet resultSet;
    private AthenaStatement parentStatement;
    private AthenaClient athenaClient;
    private List<Row> rows;
    private List<ColumnInfo> columnInfos;

    @Captor
    private ArgumentCaptor<Consumer<GetQueryResultsRequest.Builder>> getQueryResultsCaptor;

    @BeforeEach
    void setUpResultSet() {
        athenaClient = mock(AthenaClient.class);
        parentStatement = mock(AthenaStatement.class);
        resultSet = new AthenaResultSet(athenaClient, parentStatement, "Q1234");
        columnInfos = new ArrayList<>();
        rows = new ArrayList<>();
    }

    @BeforeEach
    void setUpCaptors() {
        MockitoAnnotations.initMocks(this);
    }

    @BeforeEach
    void setUpGetQueryResults() {
        when(athenaClient.getQueryResults(ArgumentMatchers.<Consumer<GetQueryResultsRequest.Builder>>any())).then(invocation -> GetQueryResultsResponse.builder().resultSet(rsb -> {
            rsb.resultSetMetadata(rsmb -> rsmb.columnInfo(columnInfos));
            rsb.rows(rows);
        }).build());
    }

    private GetQueryResultsRequest resultsRequest() {
        verify(athenaClient).getQueryResults(getQueryResultsCaptor.capture());
        GetQueryResultsRequest.Builder builder = GetQueryResultsRequest.builder();
        getQueryResultsCaptor.getValue().accept(builder);
        return builder.build();
    }

    private void noRows() {
        columnInfos.add(ColumnInfo.builder().label("col1").type("string").build());
        columnInfos.add(ColumnInfo.builder().label("col2").type("integer").build());
        rows.add(Row.builder().data(db -> db.varCharValue("col1"), db -> db.varCharValue("col2")).build());
    }

    private void addRows() {
        columnInfos.add(ColumnInfo.builder().label("col1").type("string").build());
        columnInfos.add(ColumnInfo.builder().label("col2").type("integer").build());
        rows.add(Row.builder().data(db -> db.varCharValue("col1"), db -> db.varCharValue("col2")).build());
        rows.add(Row.builder().data(db -> db.varCharValue("row1"), db -> db.varCharValue("1")).build());
        rows.add(Row.builder().data(db -> db.varCharValue("row2"), db -> db.varCharValue("2")).build());
        rows.add(Row.builder().data(db -> db.varCharValue("row3"), db -> db.varCharValue("3")).build());
    }

    @Nested
    class GetStatement {
        @Test
        void returnsTheParentStatement() throws Exception {
            assertSame(parentStatement, resultSet.getStatement());
        }
    }

    @Nested
    class GetMetaData {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void returnsMetaData() throws Exception {
            resultSet.next();
            assertEquals("col1", resultSet.getMetaData().getColumnLabel(1));
        }

        @Test
        void loadsTheMetaDataIfNotLoaded() throws Exception {
            assertNotNull(resultSet.getMetaData());
            verify(athenaClient).getQueryResults(ArgumentMatchers.<Consumer<GetQueryResultsRequest.Builder>>any());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.getMetaData());
            }
        }
    }

    @Nested
    class Next {
        @Test
        void callsGetQueryResults() throws Exception {
            resultSet.next();
            GetQueryResultsRequest request = resultsRequest();
            assertEquals("Q1234", request.queryExecutionId());
        }

        @Test
        void loadsTheMaxNumberOfRowsAllowed() throws Exception {
            resultSet.next();
            GetQueryResultsRequest request = resultsRequest();
            assertEquals(1000, request.maxResults());
        }

        @Nested
        class WhenTheResultIsEmpty {
            @BeforeEach
            void setUp() {
                noRows();
            }

            @Test
            void returnsFalse() throws Exception {
                assertFalse(resultSet.next());
            }
        }

        @Nested
        class WhenTheResultHasRows {
            @BeforeEach
            void setUp() {
                addRows();
            }

            @Test
            void returnsTrue() throws Exception {
                assertTrue(resultSet.next());
            }

            @Test
            void skipsTheHeaderRow() throws Exception {
                resultSet.next();
                assertNotEquals("col1", resultSet.getString(1));
            }

            @Test
            void returnsEachRow() throws Exception {
                resultSet.next();
                assertEquals("row1", resultSet.getString(1));
                resultSet.next();
                assertEquals("row2", resultSet.getString(1));
                resultSet.next();
                assertEquals("row3", resultSet.getString(1));
            }

            @Nested
            class AndIsExhausted {
                @Test
                void returnsFalse() throws Exception {
                    assertTrue(resultSet.next());
                    assertTrue(resultSet.next());
                    assertTrue(resultSet.next());
                    assertFalse(resultSet.next());
                }
            }
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.next());
            }
        }
    }

    @Nested
    class IsBeforeFirst {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void returnsTrueBeforeNextIsCalled() throws Exception {
            assertTrue(resultSet.isBeforeFirst());
        }

        @Test
        void returnsFalseWhenNextHasBeenCalled() throws Exception {
            resultSet.next();
            assertFalse(resultSet.isBeforeFirst());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.isBeforeFirst());
            }
        }
    }

    @Nested
    class IsFirst {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void returnsFalseBeforeNextIsCalled() throws Exception {
            assertFalse(resultSet.isFirst());
        }

        @Test
        void returnsTrueWhenNextHasBeenCalledOnce() throws Exception {
            resultSet.next();
            assertTrue(resultSet.isFirst());
        }

        @Test
        void returnsFalseWhenNextHasBeenCalledTwice() throws Exception {
            resultSet.next();
            resultSet.next();
            assertFalse(resultSet.isFirst());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.isFirst());
            }
        }
    }

    @Nested
    class IsLast {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void returnsFalseBeforeNextIsCalled() throws Exception {
            assertFalse(resultSet.isLast());
        }

        @Test
        void returnsFalseWhenNotLast() throws Exception {
            resultSet.next();
            assertFalse(resultSet.isLast());
            resultSet.next();
            assertFalse(resultSet.isLast());
        }

        @Test
        void returnsTrueWhenOnLastRow() throws Exception {
            resultSet.relative(3);
            assertTrue(resultSet.isLast());
        }

        @Test
        void returnsFalseWhenAfterLastRow() throws Exception {
            resultSet.relative(4);
            assertFalse(resultSet.isLast());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.isLast());
            }
        }
    }

    @Nested
    class IsAfterLast {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void returnsFalseBeforeNextIsCalled() throws Exception {
            assertFalse(resultSet.isAfterLast());
        }

        @Test
        void returnsFalseWhenNotAfterLast() throws Exception {
            resultSet.next();
            assertFalse(resultSet.isAfterLast());
            resultSet.next();
            assertFalse(resultSet.isAfterLast());
        }

        @Test
        void returnsFalseWhenOnLastRow() throws Exception {
            resultSet.relative(3);
            assertFalse(resultSet.isAfterLast());
        }

        @Test
        void returnsTrueWhenAfterLastRow() throws Exception {
            resultSet.relative(4);
            assertTrue(resultSet.isAfterLast());
            resultSet.next();
            assertTrue(resultSet.isAfterLast());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.isAfterLast());
            }
        }
    }

    @Nested
    class GetRow {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void returnsZeroWhenBeforeFirstRow() throws Exception {
            assertEquals(0, resultSet.getRow());
        }

        @Test
        void returnsOneWhenOnFirstRow() throws Exception {
            resultSet.next();
            assertEquals(1, resultSet.getRow());
        }

        @Test
        void returnsTheRowNumber() throws Exception {
            resultSet.next();
            assertEquals(1, resultSet.getRow());
            resultSet.next();
            assertEquals(2, resultSet.getRow());
            resultSet.next();
            assertEquals(3, resultSet.getRow());
        }

        @Test
        void returnsZeroWhenAfterLastRow() throws Exception {
            resultSet.relative(4);
            assertEquals(0, resultSet.getRow());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.getRow());
            }
        }
    }

    @Nested
    class First {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.first());
        }
    }

    @Nested
    class BeforeFirst {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.beforeFirst());
        }
    }

    @Nested
    class Last {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.last());
        }
    }

    @Nested
    class AfterLast {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.afterLast());
        }
    }

    @Nested
    class Absolute {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.absolute(3));
        }
    }

    @Nested
    class Relative {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void movesForwardMultipleRows() throws Exception {
            resultSet.relative(3);
            assertEquals("row3", resultSet.getString(1));
        }

        @Test
        void returnsTrue() throws Exception {
            assertTrue(resultSet.relative(3));
        }

        @Test
        void returnsFalseWhenNextWouldHaveReturnedFalse() throws Exception {
            assertFalse(resultSet.relative(10));
        }

        @Test
        void throwsWhenOffsetIsZero() {
            assertThrows(SQLException.class, () -> resultSet.relative(0));
        }

        @Test
        void throwsWhenOffsetIsNegative() {
            assertThrows(SQLException.class, () -> resultSet.relative(-3));
        }
    }

    @Nested
    class Previous {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.previous());
        }
    }

    @Nested
    class GetType {
        @Test
        void returnsForwardOnly() throws Exception {
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, resultSet.getType());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.getType());
            }
        }
    }

    @Nested
    class GetFetchDirection {
        @Test
        void returnsForward() throws Exception {
            assertEquals(ResultSet.FETCH_FORWARD, resultSet.getFetchDirection());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.getFetchDirection());
            }
        }
    }

    @Nested
    class SetFetchDirection {
        @Test
        void allowsFetchForward() {
            assertDoesNotThrow(() -> resultSet.setFetchDirection(ResultSet.FETCH_FORWARD));
        }

        @Test
        void doesNotAllowAnyOtherDirection() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.setFetchDirection(ResultSet.FETCH_REVERSE));
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.setFetchSize(ResultSet.FETCH_FORWARD));
            }
        }
    }

    @Nested
    class GetConcurrency {
        @Test
        void returnsReadOnly() throws Exception {
            assertEquals(ResultSet.CONCUR_READ_ONLY, resultSet.getConcurrency());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.getConcurrency());
            }
        }
    }

    @Nested
    class SetFetchSize {
        @Test
        void setsTheFetchSize() throws Exception {
            resultSet.setFetchSize(77);
            resultSet.next();
            assertEquals(77, resultsRequest().maxResults());
        }

        @Nested
        class WhenCalledWithNegativeNumber {
            @Test
            void throwsAnError() throws Exception {
                assertThrows(SQLException.class, () -> resultSet.setFetchSize(-1));
            }
        }

        @Nested
        class WhenCalledWithTooLargeNumber {
            @Test
            void throwsAnError() throws Exception {
                assertThrows(SQLException.class, () -> resultSet.setFetchSize(1001));
            }
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.setFetchSize(1));
            }
        }
    }

    @Nested
    class GetFetchSize {
        @Test
        void returnsTheDefaultFetchSize() throws Exception {
            assertTrue(resultSet.getFetchSize() > 0);
        }

        @Test
        void returnsTheConfiguredFetchSize() throws Exception {
            resultSet.setFetchSize(99);
            assertEquals(99, resultSet.getFetchSize());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.getFetchSize());
            }
        }
    }

    @Nested
    class FindColumn {
        @BeforeEach
        void setUp() {
            addRows();
        }

        @Test
        void returnsTheColumnIndex() throws Exception {
            assertEquals(1, resultSet.findColumn("col1"));
            assertEquals(2, resultSet.findColumn("col2"));
        }

        @Test
        void loadsTheMetaDataIfNotLoaded() throws Exception {
            resultSet.findColumn("col1");
            verify(athenaClient).getQueryResults(ArgumentMatchers.<Consumer<GetQueryResultsRequest.Builder>>any());
        }

        @Test
        void throwsExceptionWhenNoSuchColumnExists() {
            assertThrows(SQLException.class, () -> resultSet.findColumn("col99"));
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.findColumn("col1"));
            }
        }
    }

    @Nested
    class Unwrap {
        @Test
        void returnsTypedInstance() throws SQLException {
            AthenaResultSet ars = resultSet.unwrap(AthenaResultSet.class);
            assertNotNull(ars);
        }

        @Test
        void throwsWhenAskedToUnwrapClassItIsNotWrapperFor() {
            assertThrows(SQLException.class, () -> resultSet.unwrap(String.class));
        }
    }

    @Nested
    class IsWrapperFor {
        @Test
        void isWrapperForAthenaResultSet() throws Exception {
            assertTrue(resultSet.isWrapperFor(AthenaResultSet.class));
        }

        @Test
        void isWrapperForObject() throws Exception {
            assertTrue(resultSet.isWrapperFor(Object.class));
        }

        @Test
        void isNotWrapperForOtherClasses() throws Exception {
            assertFalse(resultSet.isWrapperFor(String.class));
        }
    }

    @Nested
    class GetHoldability {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.getHoldability());
        }
    }

    @Nested
    class GetCursorName {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.getCursorName());
        }
    }

    abstract class SharedWhenOutOfPosition<T> {
        abstract protected T get(int n) throws Exception;

        abstract protected T get(String n) throws Exception;

        @Test
        void throwsAnError() throws Exception {
            assertThrows(SQLException.class, () -> get(1));
            resultSet.next();
            assertThrows(SQLException.class, () -> get(-1));
            assertThrows(SQLException.class, () -> get(0));
            assertThrows(SQLException.class, () -> get(99));
            assertThrows(SQLException.class, () -> get("col99"));
            while (resultSet.next()) {
            }
            assertThrows(SQLException.class, () -> get(1));
        }
    }

    abstract class SharedWhenClosed<T> {
        abstract protected T get(int n) throws Exception;

        abstract protected T get(String n) throws Exception;

        @Test
        void throwsAnError() throws Exception {
            resultSet.close();
            assertThrows(SQLException.class, () -> get(1));
            assertThrows(SQLException.class, () -> get("col1"));
        }
    }

    @Nested
    class GetString {
        protected String getString(int n) throws Exception {
            return resultSet.getString(n);
        }

        protected String getString(String n) throws Exception {
            return resultSet.getString(n);
        }

        @BeforeEach
        void setUp() {
            columnInfos.add(ColumnInfo.builder().label("col1").type("string").build());
            columnInfos.add(ColumnInfo.builder().label("col2").type("integer").build());
            rows.add(Row.builder().data(db -> db.varCharValue("col1"), db -> db.varCharValue("col2")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("row1"), db -> db.varCharValue("1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("row2"), db -> db.varCharValue("2")).build());
            rows.add(Row.builder().data(db -> db.varCharValue(null), db -> db.varCharValue(null)).build());
        }

        @Test
        void returnsTheColumnAtTheSpecifiedIndexOfTheCurrentRowAsAString() throws Exception {
            resultSet.next();
            assertEquals("row1", getString(1));
            assertEquals("1", getString(2));
            resultSet.next();
            assertEquals("row2", getString(1));
            assertEquals("2", getString(2));
        }

        @Test
        void returnsTheColumnWithTheSpecifiedNameOfTheCurrentRowAsAString() throws Exception {
            resultSet.next();
            assertEquals("row1", getString("col1"));
            assertEquals("1", getString("col2"));
            resultSet.next();
            assertEquals("row2", getString("col1"));
            assertEquals("2", getString("col2"));
        }

        @Test
        void returnsNullWhenValueIsNull() throws Exception {
            resultSet.relative(3);
            assertNull(getString("col1"));
        }

        @Nested
        class WhenOutOfPosition extends SharedWhenOutOfPosition<String> {
            protected String get(int n) throws Exception {
                return getString(n);
            }

            protected String get(String n) throws Exception {
                return getString(n);
            }
        }

        @Nested
        class WhenClosed extends SharedWhenClosed<String> {
            protected String get(int n) throws Exception {
                return getString(n);
            }

            protected String get(String n) throws Exception {
                return getString(n);
            }
        }
    }

    @Nested
    class GetNString extends GetString {
        @Override
        protected String getString(int n) throws Exception {
            return resultSet.getNString(n);
        }

        @Override
        protected String getString(String n) throws Exception {
            return resultSet.getNString(n);
        }
    }

    @Nested
    class GetBoolean {
        @BeforeEach
        void setUp() {
            columnInfos.add(ColumnInfo.builder().label("col1").type("boolean").build());
            rows.add(Row.builder().data(db -> db.varCharValue("col1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("0")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("3")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("false")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("true")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("FALSE")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("TRUE")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("FaLsE")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("TruE")).build());
            rows.add(Row.builder().data(db -> db.varCharValue(null)).build());
        }

        @Test
        void returns0AsFalse() throws Exception {
            resultSet.next();
            assertFalse(resultSet.getBoolean(1));
            assertFalse(resultSet.getBoolean("col1"));
        }

        @Test
        void returns1AsTrue() throws Exception {
            resultSet.next();
            resultSet.next();
            assertTrue(resultSet.getBoolean(1));
            assertTrue(resultSet.getBoolean("col1"));
        }

        @Test
        void returnsAllOtherNumbersAsTrue() throws Exception {
            resultSet.relative(3);
            assertTrue(resultSet.getBoolean(1));
            assertTrue(resultSet.getBoolean("col1"));
            resultSet.next();
            assertTrue(resultSet.getBoolean(1));
            assertTrue(resultSet.getBoolean("col1"));
        }

        @Test
        void returnsFalseAsFalse() throws Exception {
            for (int i = 0; i < 5; i++) {
                resultSet.next();
            }
            assertFalse(resultSet.getBoolean(1));
            assertFalse(resultSet.getBoolean("col1"));
            resultSet.next();
            resultSet.next();
            assertFalse(resultSet.getBoolean(1));
            assertFalse(resultSet.getBoolean("col1"));
            resultSet.next();
            resultSet.next();
            assertFalse(resultSet.getBoolean(1));
            assertFalse(resultSet.getBoolean("col1"));
        }

        @Test
        void returnsTrueAsTrue() throws Exception {
            for (int i = 0; i < 6; i++) {
                resultSet.next();
            }
            assertTrue(resultSet.getBoolean(1));
            assertTrue(resultSet.getBoolean("col1"));
            resultSet.next();
            resultSet.next();
            assertTrue(resultSet.getBoolean(1));
            assertTrue(resultSet.getBoolean("col1"));
            resultSet.next();
            resultSet.next();
            assertTrue(resultSet.getBoolean(1));
            assertTrue(resultSet.getBoolean("col1"));
        }

        @Test
        void returnsNullAsFalse() throws Exception {
            for (int i = 0; i < 11; i++) {
                resultSet.next();
            }
            assertFalse(resultSet.getBoolean(1));
            assertFalse(resultSet.getBoolean("col1"));
        }

        @Nested
        class WhenOutOfPosition extends SharedWhenOutOfPosition<Boolean> {
            protected Boolean get(int n) throws Exception {
                return resultSet.getBoolean(n);
            }

            protected Boolean get(String n) throws Exception {
                return resultSet.getBoolean(n);
            }
        }

        @Nested
        class WhenClosed extends SharedWhenClosed<Boolean> {
            protected Boolean get(int n) throws Exception {
                return resultSet.getBoolean(n);
            }

            protected Boolean get(String n) throws Exception {
                return resultSet.getBoolean(n);
            }
        }
    }

    @Nested
    class GetByte {
        @BeforeEach
        void setUp() {
            columnInfos.add(ColumnInfo.builder().label("col1").type("tinyint").build());
            rows.add(Row.builder().data(db -> db.varCharValue("col1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("0")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("123")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("2342345442342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-2342345442342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("fnord")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("")).build());
            rows.add(Row.builder().data(db -> db.varCharValue(null)).build());
        }

        @Test
        void returnsTheIntegerValueOfTheSpecifiedColumn() throws Exception {
            resultSet.next();
            assertEquals(0, resultSet.getByte(1));
            resultSet.next();
            assertEquals(-1, resultSet.getByte("col1"));
            resultSet.next();
            assertEquals(123, resultSet.getByte(1));
        }

        @Test
        void throwsAnErrorWhenNotAByte() throws Exception {
            resultSet.relative(4);
            assertThrows(SQLDataException.class, () -> resultSet.getByte(1));
            assertThrows(SQLDataException.class, () -> resultSet.getByte("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getByte(1));
            assertThrows(SQLDataException.class, () -> resultSet.getByte("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getByte(1));
            assertThrows(SQLDataException.class, () -> resultSet.getByte("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getByte(1));
            assertThrows(SQLDataException.class, () -> resultSet.getByte("col1"));
        }

        @Test
        void returnsNullAsZero() throws Exception {
            resultSet.relative(8);
            assertEquals(0, resultSet.getByte(1));
            assertEquals(0, resultSet.getByte("col1"));
        }

        @Nested
        class WhenOutOfPosition extends SharedWhenOutOfPosition<Byte> {
            protected Byte get(int n) throws Exception {
                return resultSet.getByte(n);
            }

            protected Byte get(String n) throws Exception {
                return resultSet.getByte(n);
            }
        }

        @Nested
        class WhenClosed extends SharedWhenClosed<Byte> {
            protected Byte get(int n) throws Exception {
                return resultSet.getByte(n);
            }

            protected Byte get(String n) throws Exception {
                return resultSet.getByte(n);
            }
        }
    }

    @Nested
    class GetShort {
        @BeforeEach
        void setUp() {
            columnInfos.add(ColumnInfo.builder().label("col1").type("smallint").build());
            rows.add(Row.builder().data(db -> db.varCharValue("col1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("0")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("30123")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("2342345442342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-2342345442342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("fnord")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("")).build());
            rows.add(Row.builder().data(db -> db.varCharValue(null)).build());
        }

        @Test
        void returnsTheIntegerValueOfTheSpecifiedColumn() throws Exception {
            resultSet.next();
            assertEquals(0, resultSet.getShort(1));
            resultSet.next();
            assertEquals(-1, resultSet.getShort("col1"));
            resultSet.next();
            assertEquals(30123, resultSet.getShort(1));
        }

        @Test
        void throwsAnErrorWhenNotAByte() throws Exception {
            resultSet.relative(4);
            assertThrows(SQLDataException.class, () -> resultSet.getShort(1));
            assertThrows(SQLDataException.class, () -> resultSet.getShort("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getShort(1));
            assertThrows(SQLDataException.class, () -> resultSet.getShort("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getShort(1));
            assertThrows(SQLDataException.class, () -> resultSet.getShort("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getShort(1));
            assertThrows(SQLDataException.class, () -> resultSet.getShort("col1"));
        }

        @Test
        void returnsNullAsZero() throws Exception {
            resultSet.relative(8);
            assertEquals(0, resultSet.getShort(1));
            assertEquals(0, resultSet.getShort("col1"));
        }

        @Nested
        class WhenOutOfPosition extends SharedWhenOutOfPosition<Short> {
            protected Short get(int n) throws Exception {
                return resultSet.getShort(n);
            }

            protected Short get(String n) throws Exception {
                return resultSet.getShort(n);
            }
        }

        @Nested
        class WhenClosed extends SharedWhenClosed<Short> {
            protected Short get(int n) throws Exception {
                return resultSet.getShort(n);
            }

            protected Short get(String n) throws Exception {
                return resultSet.getShort(n);
            }
        }
    }

    @Nested
    class GetInt {
        @BeforeEach
        void setUp() {
            columnInfos.add(ColumnInfo.builder().label("col1").type("integer").build());
            rows.add(Row.builder().data(db -> db.varCharValue("col1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("0")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("234234544")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("2342345442342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-2342345442342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("fnord")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("")).build());
            rows.add(Row.builder().data(db -> db.varCharValue(null)).build());
        }

        @Test
        void returnsTheIntegerValueOfTheSpecifiedColumn() throws Exception {
            resultSet.next();
            assertEquals(0, resultSet.getInt(1));
            resultSet.next();
            assertEquals(-1, resultSet.getInt("col1"));
            resultSet.next();
            assertEquals(234234544, resultSet.getInt(1));
        }

        @Test
        void throwsAnErrorWhenNotAnInteger() throws Exception {
            resultSet.relative(4);
            assertThrows(SQLDataException.class, () -> resultSet.getInt(1));
            assertThrows(SQLDataException.class, () -> resultSet.getInt("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getInt(1));
            assertThrows(SQLDataException.class, () -> resultSet.getInt("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getInt(1));
            assertThrows(SQLDataException.class, () -> resultSet.getInt("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getInt(1));
            assertThrows(SQLDataException.class, () -> resultSet.getInt("col1"));
        }

        @Test
        void returnsNullAsZero() throws Exception {
            resultSet.relative(8);
            assertEquals(0, resultSet.getInt(1));
            assertEquals(0, resultSet.getInt("col1"));
        }

        @Nested
        class WhenOutOfPosition extends SharedWhenOutOfPosition<Integer> {
            protected Integer get(int n) throws Exception {
                return resultSet.getInt(n);
            }

            protected Integer get(String n) throws Exception {
                return resultSet.getInt(n);
            }
        }

        @Nested
        class WhenClosed extends SharedWhenClosed<Integer> {
            protected Integer get(int n) throws Exception {
                return resultSet.getInt(n);
            }

            protected Integer get(String n) throws Exception {
                return resultSet.getInt(n);
            }
        }
    }

    @Nested
    class GetLong {
        @BeforeEach
        void setUp() {
            columnInfos.add(ColumnInfo.builder().label("col1").type("bigint").build());
            rows.add(Row.builder().data(db -> db.varCharValue("col1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("0")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-1")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("234234523423423444")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("2342345342342342234234234442342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("-2342345442342342342342342342342345")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("fnord")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("")).build());
            rows.add(Row.builder().data(db -> db.varCharValue(null)).build());
        }

        @Test
        void returnsTheIntegerValueOfTheSpecifiedColumn() throws Exception {
            resultSet.next();
            assertEquals(0, resultSet.getLong(1));
            resultSet.next();
            assertEquals(-1, resultSet.getLong("col1"));
            resultSet.next();
            assertEquals(234234523423423444L, resultSet.getLong(1));
        }

        @Test
        void throwsAnErrorWhenNotAnInteger() throws Exception {
            resultSet.relative(4);
            assertThrows(SQLDataException.class, () -> resultSet.getLong(1));
            assertThrows(SQLDataException.class, () -> resultSet.getLong("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getLong(1));
            assertThrows(SQLDataException.class, () -> resultSet.getLong("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getLong(1));
            assertThrows(SQLDataException.class, () -> resultSet.getLong("col1"));
            resultSet.next();
            assertThrows(SQLDataException.class, () -> resultSet.getLong(1));
            assertThrows(SQLDataException.class, () -> resultSet.getLong("col1"));
        }

        @Test
        void returnsNullAsZero() throws Exception {
            resultSet.relative(8);
            assertEquals(0, resultSet.getLong(1));
            assertEquals(0, resultSet.getLong("col1"));
        }

        @Nested
        class WhenOutOfPosition extends SharedWhenOutOfPosition<Long> {
            protected Long get(int n) throws Exception {
                return resultSet.getLong(n);
            }

            protected Long get(String n) throws Exception {
                return resultSet.getLong(n);
            }
        }

        @Nested
        class WhenClosed extends SharedWhenClosed<Long> {
            protected Long get(int n) throws Exception {
                return resultSet.getLong(n);
            }

            protected Long get(String n) throws Exception {
                return resultSet.getLong(n);
            }
        }
    }

    @Nested
    class WasNull {
        @BeforeEach
        void setUp() {
            columnInfos.add(ColumnInfo.builder().label("col1").type("boolean").build());
            columnInfos.add(ColumnInfo.builder().label("col2").type("integer").build());
            rows.add(Row.builder().data(db -> db.varCharValue("col1"), db -> db.varCharValue("col2")).build());
            rows.add(Row.builder().data(db -> db.varCharValue("true"), db -> db.varCharValue(null)).build());
            rows.add(Row.builder().data(db -> db.varCharValue(null), db -> db.varCharValue("1")).build());
        }

        @Test
        void returnsFalseBeforeAnyValueIsRead() throws Exception {
            assertFalse(resultSet.wasNull());
        }

        @Test
        void returnsTrueWhenTheLastReadValueWasNull() throws Exception {
            resultSet.next();
            resultSet.getInt(2);
            assertTrue(resultSet.wasNull());
            resultSet.next();
            resultSet.getBoolean(1);
            assertTrue(resultSet.wasNull());
        }

        @Test
        void returnsFalseWhenTheLastReadValueWasNotNull() throws Exception {
            resultSet.next();
            resultSet.getBoolean(1);
            assertFalse(resultSet.wasNull());
            resultSet.next();
            resultSet.getInt(2);
            assertFalse(resultSet.wasNull());
        }

        @Test
        void resetsWhenANonNullValueIsRead() throws Exception {
            resultSet.next();
            resultSet.getInt(2);
            resultSet.getBoolean(1);
            assertFalse(resultSet.wasNull());
        }
    }

    @Nested
    class MutationQueries {
        @Test
        void returnFalse() throws Exception {
            assertFalse(resultSet.rowUpdated());
            assertFalse(resultSet.rowInserted());
            assertFalse(resultSet.rowDeleted());
        }

        @Nested
        class WhenClosed {
            @Test
            void throwsAnError() throws Exception {
                resultSet.close();
                assertThrows(SQLException.class, () -> resultSet.rowUpdated());
                assertThrows(SQLException.class, () -> resultSet.rowInserted());
                assertThrows(SQLException.class, () -> resultSet.rowDeleted());
            }
        }
    }

    @Nested
    class Mutations {
        @Test
        void isNotSupported() {
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.insertRow());
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateRow());
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.deleteRow());
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.refreshRow());
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.cancelRowUpdates());
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.moveToInsertRow());
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.moveToCurrentRow());
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNull(1));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNull(""));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBoolean(1, true));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBoolean("", true));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateByte(1, (byte) 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateByte("", (byte) 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateShort(1, (short) 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateShort("", (short) 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateInt(1, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateInt("", 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateLong(1, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateLong("", 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateFloat(1, 0.0f));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateFloat("", 0.0f));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateDouble(1, 0.0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateDouble("", 0.0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBigDecimal(1, new BigDecimal(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBigDecimal("", new BigDecimal(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateString(1, ""));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateString("", ""));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBytes(1, new byte[0]));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBytes("", new byte[0]));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateDate(1, new Date(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateDate("", new Date(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateTime(1, new Time(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateTime("", new Time(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateTimestamp(1, new Timestamp(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateTimestamp("", new Timestamp(0)));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateObject(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateObject("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateObject(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateObject("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateRef(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateRef("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBlob(1, new ByteArrayInputStream(new byte[0])));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBlob(1, new ByteArrayInputStream(new byte[0]), 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBlob(1, (Blob) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBlob("", new ByteArrayInputStream(new byte[0])));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBlob("", new ByteArrayInputStream(new byte[0]), 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBlob("", (Blob) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateClob(1, (Reader) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateClob(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateClob(1, (Clob) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateClob("", (Reader) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateClob("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateClob("", (Clob) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateArray(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateArray("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateRowId(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateRowId("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNString(1, ""));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNString("", ""));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNClob(1, (Reader) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNClob(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNClob(1, (NClob) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNClob("", (Reader) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNClob("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNClob("", (NClob) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateSQLXML(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateSQLXML("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNCharacterStream(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNCharacterStream(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNCharacterStream("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateNCharacterStream("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream(1, null, 0L));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateAsciiStream("", null, 0L));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream(1, null, 0L));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateBinaryStream("", null, 0L));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream(1, null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream(1, null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream(1, null, 0L));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream("", null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream("", null, 0));
            assertThrows(SQLFeatureNotSupportedException.class, () -> resultSet.updateCharacterStream("", null, 0L));
        }
    }
}