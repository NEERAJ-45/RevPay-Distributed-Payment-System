package com.neeraj.upi.user.kafka;

import com.neeraj.upi.common.constants.KafkaTopics;
import com.neeraj.upi.user.event.UserCreatedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventPublisherTest {

    @Mock
    private KafkaTemplate<String, UserCreatedEvent> kafkaTemplate;

    @Test
    @DisplayName("publishUserCreated should send event to kafka")
    void publishUserCreated_sendsEvent() {
        UserEventPublisher publisher = new UserEventPublisher(kafkaTemplate);

        UserCreatedEvent event = UserCreatedEvent.builder()
                .userId(UUID.randomUUID())
                .upiId("test@miniupi")
                .fullName("Test")
                .phone("9876543210")
                .createdAt(Instant.now())
                .build();

        RecordMetadata metadata = new RecordMetadata(new TopicPartition(KafkaTopics.USER_CREATED, 0), 0L, 0, 0L, 0, 0);
        SendResult<String, UserCreatedEvent> sendResult = new SendResult<>(new ProducerRecord<>(KafkaTopics.USER_CREATED, event.getUserId().toString(), event), metadata);
        CompletableFuture<SendResult<String, UserCreatedEvent>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq(KafkaTopics.USER_CREATED), eq(event.getUserId().toString()), eq(event))).thenReturn(future);

        publisher.publishUserCreated(event);

        verify(kafkaTemplate).send(KafkaTopics.USER_CREATED, event.getUserId().toString(), event);
    }
}
