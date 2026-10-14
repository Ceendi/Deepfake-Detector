package com.deepfake.orchestrator.config;

import com.deepfake.orchestrator.listener.InvalidResultException;
import com.deepfake.orchestrator.listener.PoisonDeadLetterException;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

public final class AmqpFailurePolicy {
    private AmqpFailurePolicy() { }

    public static boolean databaseUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException
                    || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof QueryTimeoutException
                    || cause instanceof SQLException sql && connectionLost(sql.getSQLState())) return true;
        }
        return false;
    }

    // A server shutdown/restart kills open sessions with 57P01-57P03, which Hibernate reports as a
    // generic JDBC error rather than a resource failure.
    private static boolean connectionLost(String sqlState) {
        return sqlState != null && (sqlState.startsWith("08")
                || sqlState.equals("57P01") || sqlState.equals("57P02") || sqlState.equals("57P03"));
    }

    public static boolean retryInProcess(Throwable failure) {
        if (databaseUnavailable(failure)) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidResultException || cause instanceof PoisonDeadLetterException
                    || cause instanceof ClassCastException || cause instanceof NumberFormatException) return false;
        }
        return true;
    }
}
