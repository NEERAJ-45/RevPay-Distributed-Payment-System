package com.neeraj.upi.transaction.kafka;

import com.neeraj.upi.common.constants.KafkaTopics;
import com.neeraj.upi.transaction.event.TransactionCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes transaction outcome events to Kafka.
 * Consumed by notification-service for debit/credit SMS alerts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionEventPublisher {

    private final KafkaTemplate<String, TransactionCompletedEvent> kafkaTemplate;

    public void publish(TransactionCompletedEvent event) {
        String topic = "SUCCESS".equals(event.getStatus()) ? KafkaTopics.TXN_COMPLETED : KafkaTopics.TXN_FAILED;
        kafkaTemplate.send(topic, event.getTxnId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event for txnId={}, topic={}: {}", event.getTxnId(), topic, ex.getMessage());
                    } else {
                        log.info("Published event for txnId={} to topic={}, offset={}", event.getTxnId(), topic, result.getRecordMetadata().offset());
                    }
                });
    }

    public void publishSync(TransactionCompletedEvent event) {
        String topic = "SUCCESS".equals(event.getStatus()) ? KafkaTopics.TXN_COMPLETED : KafkaTopics.TXN_FAILED;
        try {
            var result = kafkaTemplate.send(topic, event.getTxnId().toString(), event).get();
            log.info("Published event for txnId={} to topic={}, offset={}", event.getTxnId(), topic, result.getRecordMetadata().offset());
        } catch (Exception e) {
            log.error("Failed to publish event for txnId={}, topic={}: {}", event.getTxnId(), topic, e.getMessage());
            throw new RuntimeException("Kafka publish failed for txnId=" + event.getTxnId(), e);
        }
    }
}
