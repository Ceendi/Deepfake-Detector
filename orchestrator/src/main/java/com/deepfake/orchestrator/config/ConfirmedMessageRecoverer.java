package com.deepfake.orchestrator.config;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecovererWithConfirms;

/** AUTO acknowledgement is safe only after mandatory routing and publisher acknowledgement. */
public final class ConfirmedMessageRecoverer implements MessageRecoverer {
    private final RepublishMessageRecovererWithConfirms delegate;
    private final AmqpRecoveryControl recovery;
    private final String listenerId;

    public ConfirmedMessageRecoverer(RabbitTemplate template, String destination,
            long confirmTimeoutMs, AmqpRecoveryControl recovery, String listenerId) {
        if (confirmTimeoutMs < 1) throw new IllegalArgumentException("Confirm timeout must be positive");
        this.delegate = new RepublishMessageRecovererWithConfirms(
                template, RabbitConfig.DLX, destination, ConfirmType.CORRELATED);
        this.delegate.setConfirmTimeout(confirmTimeoutMs);
        this.recovery = recovery;
        this.listenerId = listenerId;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        try {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            delegate.recover(message, cause);
            recovery.succeeded(listenerId);
        } catch (RuntimeException publicationFailure) {
            try {
                recovery.publicationFailed(listenerId);
            } catch (RuntimeException stopFailure) {
                publicationFailure.addSuppressed(stopFailure);
            }
            // Overrides default-requeue-rejected=false. AUTO mode ensures rejection/requeue is
            // applied; if the transport is unavailable, closing the channel requeues the unacked original.
            throw new ImmediateRequeueAmqpException("Recovery publication did not succeed", publicationFailure);
        }
    }
}
