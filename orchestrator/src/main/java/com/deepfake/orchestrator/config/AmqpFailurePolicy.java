package com.deepfake.orchestrator.config;

import com.deepfake.orchestrator.listener.InvalidResultException;
import com.deepfake.orchestrator.listener.PoisonDeadLetterException;
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
                    || cause instanceof QueryTimeoutException) return true;
        }
        return false;
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
