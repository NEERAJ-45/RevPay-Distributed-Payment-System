package com.neeraj.upi.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeraj.upi.transaction.entity.OutboxEvent;
import com.neeraj.upi.transaction.event.TransactionCompletedEvent;
import com.neeraj.upi.transaction.kafka.TransactionEventPublisher;
import com.neeraj.upi.transaction.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class OutboxScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final TransactionEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> unprocessed = outboxEventRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : unprocessed) {
            try {
                TransactionCompletedEvent payload = objectMapper.readValue(event.getPayload(), TransactionCompletedEvent.class);
                eventPublisher.publishSync(payload);
                event.setProcessed(true);
                event.setProcessedAt(Instant.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to process outbox event id={}, will retry: {}", event.getId(), e.getMessage());
            }
        }
    }
}
