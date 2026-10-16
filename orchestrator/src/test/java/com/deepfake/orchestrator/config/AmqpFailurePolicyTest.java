package com.deepfake.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class AmqpFailurePolicyTest {
    static Stream<RuntimeException> deterministicFailures() {
        return Stream.of(new IllegalArgumentException("invalid value"),
                new jakarta.validation.ValidationException("invalid result"),
                new org.springframework.amqp.support.converter.MessageConversionException("invalid JSON"),
                new org.springframework.messaging.converter.MessageConversionException("invalid payload"));
    }

    @ParameterizedTest
    @MethodSource("deterministicFailures")
    void deterministicFailuresBypassRetry(RuntimeException failure) {
        var wrapped = new IllegalStateException("listener failed", failure);
        assertThat(AmqpFailurePolicy.databaseUnavailable(wrapped)).isFalse();
        assertThat(AmqpFailurePolicy.retryInProcess(wrapped)).isFalse();
    }

    static Stream<RuntimeException> redisFailures() {
        var connection = new org.springframework.data.redis.RedisConnectionFailureException("Redis offline",
                new SQLException("connection failure", "08006"));
        return Stream.of(connection,
                new DataAccessResourceFailureException("Redis operation failed", connection),
                new QueryTimeoutException("Redis timeout", new io.lettuce.core.RedisCommandTimeoutException("timeout")),
                new CannotCreateTransactionException("Redis transaction failed", connection),
                new org.springframework.data.redis.RedisSystemException("Redis failed", connection));
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void redisFailuresNeverPauseDatabaseListener(RuntimeException failure) {
        assertThat(AmqpFailurePolicy.databaseUnavailable(new IllegalStateException("listener failed", failure))).isFalse();
    }

    static Stream<RuntimeException> databaseFailures() {
        return Stream.of(new CannotCreateTransactionException("offline"),
                new CannotGetJdbcConnectionException("offline"),
                new DataAccessResourceFailureException("offline"),
                new TransientDataAccessResourceException("offline"), new QueryTimeoutException("offline"),
                new JpaSystemException(new RuntimeException(
                        new SQLException("terminating connection due to administrator command", "57P01"))),
                new JpaSystemException(new RuntimeException(new SQLException("connection failure", "08006"))),
                new TransactionSystemException("Could not roll back JPA transaction",
                        new IllegalStateException(new SQLException("Connection is closed"))),
                // Observed when PostgreSQL kills the session mid-update: the rollback failure wins.
                new JpaSystemException(new org.hibernate.TransactionException(
                        "Unable to rollback against JDBC Connection", new SQLException("Connection is closed"))),
                rollbackOverriding(new JpaSystemException(new RuntimeException(
                        new SQLException("terminating connection due to administrator command", "57P01")))));
    }

    private static TransactionSystemException rollbackOverriding(RuntimeException original) {
        var rollback = new TransactionSystemException("Rollback failed");
        rollback.initApplicationException(original);
        return rollback;
    }

    @ParameterizedTest
    @MethodSource("databaseFailures")
    void wrappedDatabaseFailureRequeuesWithoutPublishing(RuntimeException failure) {
        var wrapped = new IllegalStateException("listener failed", failure);
        assertThat(AmqpFailurePolicy.databaseUnavailable(wrapped)).isTrue();
        assertThat(AmqpFailurePolicy.retryInProcess(wrapped)).isFalse();
        var rabbit = mock(RabbitTemplate.class);
        var recovery = mock(AmqpRecoveryControl.class);
        var recoverer = new ConfirmedMessageRecoverer(rabbit, RabbitConfig.Q_RESULTS_DLQ,
                1000, recovery, RabbitConfig.RESULTS_LISTENER);
        assertThatThrownBy(() -> recoverer.recover(new Message(new byte[0], new MessageProperties()), wrapped))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
        verify(recovery).databaseFailed(RabbitConfig.RESULTS_LISTENER);
        verifyNoInteractions(rabbit);
        assertThat(AmqpFailurePolicy.databaseUnavailable(new DataIntegrityViolationException("invalid data"))).isFalse();
        assertThat(AmqpFailurePolicy.retryInProcess(new DataIntegrityViolationException("invalid data"))).isTrue();
        var constraint = new JpaSystemException(new RuntimeException(new SQLException("duplicate key", "23505")));
        assertThat(AmqpFailurePolicy.databaseUnavailable(constraint)).isFalse();
        // A failed commit (e.g. a deferred constraint) stays a data failure with finite retries.
        var commit = new TransactionSystemException("Could not commit JPA transaction",
                new IllegalStateException("deferred constraint violated"));
        assertThat(AmqpFailurePolicy.databaseUnavailable(commit)).isFalse();
        assertThat(AmqpFailurePolicy.databaseUnavailable(rollbackOverriding(
                new DataIntegrityViolationException("invalid data")))).isFalse();
    }
}
