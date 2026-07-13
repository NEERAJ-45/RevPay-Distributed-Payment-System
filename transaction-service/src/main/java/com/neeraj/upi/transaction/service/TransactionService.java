package com.neeraj.upi.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeraj.upi.transaction.dto.PayRequest;
import com.neeraj.upi.transaction.dto.PayResponse;
import com.neeraj.upi.transaction.dto.TransferRequest;
import com.neeraj.upi.transaction.entity.OutboxEvent;
import com.neeraj.upi.transaction.entity.Transaction;
import com.neeraj.upi.transaction.event.TransactionCompletedEvent;
import com.neeraj.upi.transaction.feign.WalletFeignClient;
import com.neeraj.upi.transaction.repository.OutboxEventRepository;
import com.neeraj.upi.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class TransactionService {

    private final TransactionRepository  transactionRepository;
    private final OutboxEventRepository  outboxEventRepository;
    private final IdempotencyService     idempotencyService;
    private final FraudEngine            fraudEngine;
    private final WalletFeignClient      walletFeignClient;
    private final ObjectMapper objectMapper;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer successTimer;
    private final Timer failureTimer;
    private final Timer replayTimer;

    public TransactionService(TransactionRepository transactionRepository,
                               OutboxEventRepository outboxEventRepository,
                               IdempotencyService idempotencyService,
                               FraudEngine fraudEngine,
                               WalletFeignClient walletFeignClient,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyService = idempotencyService;
        this.fraudEngine = fraudEngine;
        this.walletFeignClient = walletFeignClient;
        this.objectMapper = objectMapper;
        this.successCounter = Counter.builder("upi.payments.count").tag("status", "SUCCESS").register(meterRegistry);
        this.failureCounter = Counter.builder("upi.payments.count").tag("status", "FAILED").register(meterRegistry);
        this.successTimer = Timer.builder("upi.payments.latency").tag("status", "SUCCESS").register(meterRegistry);
        this.failureTimer = Timer.builder("upi.payments.latency").tag("status", "FAILED").register(meterRegistry);
        this.replayTimer = Timer.builder("upi.payments.latency").tag("status", "REPLAYED").register(meterRegistry);
    }

    @Transactional
    public PayResponse pay(PayRequest request, String senderUpiId) {
        Timer.Sample sample = Timer.start();

        try {
            String requestId = request.getRequestId();
            var existing = idempotencyService.getExistingResult(requestId);
            if (existing.isPresent()) {
                UUID txnId = UUID.fromString(existing.get());
                Transaction txn = transactionRepository.findById(txnId)
                        .orElseThrow(() -> new RuntimeException("Idempotency key exists but txn not found: " + txnId));
                sample.stop(replayTimer);
                return toResponse(txn, true);
            }

            Transaction txn = Transaction.builder()
                    .requestId(requestId)
                    .senderUpiId(senderUpiId)
                    .receiverUpiId(request.getToUpiId())
                    .amount(request.getAmount())
                    .note(request.getNote())
                    .status(Transaction.TransactionStatus.PENDING)
                    .build();
            txn = transactionRepository.save(txn);

            fraudEngine.validate(senderUpiId, request.getToUpiId(), request.getAmount());

            TransferRequest transferReq = new TransferRequest();
            transferReq.setTransactionId(txn.getId());
            transferReq.setFromUpiId(senderUpiId);
            transferReq.setToUpiId(request.getToUpiId());
            transferReq.setAmount(request.getAmount());
            transferReq.setNote(request.getNote());
            walletFeignClient.transfer(transferReq);

            txn.setStatus(Transaction.TransactionStatus.SUCCESS);
            transactionRepository.save(txn);

            idempotencyService.storeResult(requestId, txn.getId().toString());

            saveOutboxEvent(txn, "SUCCESS", null);

            sample.stop(successTimer);
            successCounter.increment();
            return toResponse(txn, false);

        } catch (Exception e) {
            sample.stop(failureTimer);
            failureCounter.increment();
            log.error("Payment failed: {}", e.getMessage());

            Transaction txn = transactionRepository.findByRequestId(request.getRequestId()).orElse(null);
            if (txn != null) {
                txn.setStatus(Transaction.TransactionStatus.FAILED);
                txn.setFailureReason(e.getMessage());
                transactionRepository.save(txn);
                saveOutboxEvent(txn, "FAILED", e.getMessage());
            }

            throw e;
        }
    }

    private void saveOutboxEvent(Transaction txn, String status, String failureReason) {
        try {
            TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                    .txnId(txn.getId())
                    .requestId(txn.getRequestId())
                    .senderUpiId(txn.getSenderUpiId())
                    .receiverUpiId(txn.getReceiverUpiId())
                    .amount(txn.getAmount())
                    .status(status)
                    .failureReason(failureReason)
                    .completedAt(Instant.now())
                    .build();

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateId(txn.getId().toString())
                    .aggregateType("TRANSACTION")
                    .eventType("TRANSACTION_" + status)
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            outboxEventRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event for txnId={}: {}", txn.getId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PayResponse getById(UUID txnId) {
        Transaction txn = transactionRepository.findById(txnId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + txnId));
        return toResponse(txn, false);
    }

    @Transactional(readOnly = true)
    public Page<PayResponse> getHistory(String upiId, int page, int size) {
        Page<Transaction> txnPage = transactionRepository.findHistoryByUpiId(upiId, PageRequest.of(page, size));
        return txnPage.map(txn -> toResponse(txn, false));
    }

    private PayResponse toResponse(Transaction txn, boolean replayed) {
        return PayResponse.builder()
                .txnId(txn.getId())
                .requestId(txn.getRequestId())
                .senderUpiId(txn.getSenderUpiId())
                .receiverUpiId(txn.getReceiverUpiId())
                .amount(txn.getAmount())
                .note(txn.getNote())
                .status(txn.getStatus())
                .failureReason(txn.getFailureReason())
                .createdAt(txn.getCreatedAt())
                .replayed(replayed)
                .build();
    }
}
