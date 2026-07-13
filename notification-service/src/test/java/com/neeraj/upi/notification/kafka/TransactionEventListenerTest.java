package com.neeraj.upi.notification.kafka;

import com.neeraj.upi.notification.dto.TransactionCompletedEvent;
import com.neeraj.upi.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class TransactionEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private TransactionEventListener listener;

    @Test
    public void testOnTransactionCompletedEvent() {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .txnId(UUID.randomUUID())
                .requestId(UUID.randomUUID().toString())
                .senderUpiId("sender@upi")
                .receiverUpiId("receiver@upi")
                .amount(BigDecimal.valueOf(100))
                .status("SUCCESS")
                .completedAt(Instant.now())
                .build();

        listener.onTransactionEvent(event);

        verify(notificationService).sendDebitAlert(event.getSenderUpiId(), event.getSenderUpiId(),
                event.getAmount(), event.getTxnId().toString());
        verify(notificationService).sendCreditAlert(event.getReceiverUpiId(), event.getReceiverUpiId(),
                event.getAmount(), event.getTxnId().toString());
    }

    @Test
    public void testOnTransactionFailedEvent() {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .txnId(UUID.randomUUID())
                .requestId(UUID.randomUUID().toString())
                .senderUpiId("sender@upi")
                .receiverUpiId("receiver@upi")
                .amount(BigDecimal.valueOf(100))
                .status("FAILED")
                .failureReason("Insufficient balance")
                .completedAt(Instant.now())
                .build();

        listener.onTransactionEvent(event);

        verify(notificationService).sendFailureAlert(event.getSenderUpiId(),
                event.getAmount(), event.getFailureReason());
    }
}
