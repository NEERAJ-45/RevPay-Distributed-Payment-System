package com.neeraj.upi.notification.kafka;

import com.neeraj.upi.common.constants.KafkaTopics;
import com.neeraj.upi.notification.dto.TransactionCompletedEvent;
import com.neeraj.upi.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionEventListener {

    private final NotificationService notificationService;

    @KafkaListener(
        topics = {KafkaTopics.TXN_COMPLETED, KafkaTopics.TXN_FAILED},
        groupId = KafkaTopics.GROUP_NOTIFICATION
    )
    public void onTransactionEvent(TransactionCompletedEvent event) {
        String txnId = event.getTxnId().toString();
        if ("SUCCESS".equals(event.getStatus())) {
            log.info("Transaction SUCCESS: txnId={}, sender={}, receiver={}, amount={}",
                    txnId, event.getSenderUpiId(), event.getReceiverUpiId(), event.getAmount());
            notificationService.sendDebitAlert(event.getSenderUpiId(), event.getSenderUpiId(), event.getAmount(), txnId);
            notificationService.sendCreditAlert(event.getReceiverUpiId(), event.getReceiverUpiId(), event.getAmount(), txnId);
        } else {
            log.warn("Transaction FAILED: txnId={}, reason={}", txnId, event.getFailureReason());
            notificationService.sendFailureAlert(event.getSenderUpiId(), event.getAmount(), event.getFailureReason());
        }
    }
}
