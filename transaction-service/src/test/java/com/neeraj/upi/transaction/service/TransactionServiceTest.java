package com.neeraj.upi.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeraj.upi.transaction.dto.PayRequest;
import com.neeraj.upi.transaction.dto.PayResponse;
import com.neeraj.upi.transaction.entity.Transaction;
import com.neeraj.upi.transaction.exception.FraudVelocityException;
import com.neeraj.upi.transaction.feign.WalletFeignClient;
import com.neeraj.upi.transaction.repository.OutboxEventRepository;
import com.neeraj.upi.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private FraudEngine fraudEngine;
    @Mock
    private WalletFeignClient walletFeignClient;
    @Mock
    private ObjectMapper objectMapper;

    private MeterRegistry meterRegistry;
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        transactionService = new TransactionService(transactionRepository, outboxEventRepository,
                idempotencyService, fraudEngine, walletFeignClient, objectMapper, meterRegistry);
    }

    @Test
    public void testPaySuccess() {
        String requestId = UUID.randomUUID().toString();
        PayRequest request = new PayRequest();
        request.setRequestId(requestId);
        request.setToUpiId("receiver@upi");
        request.setAmount(BigDecimal.valueOf(100));

        when(idempotencyService.getExistingResult(requestId)).thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        when(transactionRepository.findById(any(UUID.class))).thenAnswer(inv -> Optional.empty());

        PayResponse response = transactionService.pay(request, "sender@upi");

        assertNotNull(response);
        assertEquals(requestId, response.getRequestId());
        verify(fraudEngine).validate(anyString(), anyString(), any());
        verify(walletFeignClient).transfer(any());
    }

    @Test
    public void testPayIdempotentReplay() {
        String requestId = UUID.randomUUID().toString();
        UUID txnId = UUID.randomUUID();

        PayRequest request = new PayRequest();
        request.setRequestId(requestId);
        request.setToUpiId("receiver@upi");
        request.setAmount(BigDecimal.valueOf(100));

        Transaction existingTxn = Transaction.builder()
                .id(txnId)
                .requestId(requestId)
                .senderUpiId("sender@upi")
                .receiverUpiId("receiver@upi")
                .amount(BigDecimal.valueOf(100))
                .status(Transaction.TransactionStatus.SUCCESS)
                .build();

        when(idempotencyService.getExistingResult(requestId)).thenReturn(Optional.of(txnId.toString()));
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existingTxn));

        PayResponse response = transactionService.pay(request, "sender@upi");

        assertTrue(response.isReplayed());
        assertEquals(txnId, response.getTxnId());
        verify(fraudEngine, never()).validate(anyString(), anyString(), any());
        verify(walletFeignClient, never()).transfer(any());
    }

    @Test
    public void testPayFraudFailure() {
        String requestId = UUID.randomUUID().toString();
        PayRequest request = new PayRequest();
        request.setRequestId(requestId);
        request.setToUpiId("receiver@upi");
        request.setAmount(BigDecimal.valueOf(100));

        when(idempotencyService.getExistingResult(requestId)).thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        doThrow(new FraudVelocityException("DAILY_LIMIT_EXCEEDED", "Daily limit exceeded"))
                .when(fraudEngine).validate(anyString(), anyString(), any());

        assertThrows(FraudVelocityException.class, () ->
                transactionService.pay(request, "sender@upi"));
    }
}
