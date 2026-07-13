package com.neeraj.upi.wallet.kafka;

import com.neeraj.upi.common.constants.KafkaTopics;
import com.neeraj.upi.wallet.dto.UserCreatedEvent;
import com.neeraj.upi.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserCreatedListener {

    private final WalletService walletService;

    @KafkaListener(topics = KafkaTopics.USER_CREATED, groupId = KafkaTopics.GROUP_WALLET)
    public void onUserCreated(UserCreatedEvent event) {
        log.info("Received UserCreated for upiId={}", event.getUpiId());
        walletService.createWallet(event.getUserId(), event.getUpiId());
    }
}
