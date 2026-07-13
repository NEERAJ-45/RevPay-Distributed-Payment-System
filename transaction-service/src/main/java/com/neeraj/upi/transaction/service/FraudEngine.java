package com.neeraj.upi.transaction.service;

import com.neeraj.upi.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Velocity and fraud rule checks.
 *
 * Rules enforced:
 *  1. Daily limit  — total outgoing amount per user per calendar day <= ₹10,000 (configurable)
 *  2. Per-txn cap  — single payment amount <= ₹50,000 (configurable)
 *  3. Self-pay     — sender and receiver UPI IDs must be different
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudEngine {

    private final TransactionRepository transactionRepository;

    @Value("${fraud.daily-limit:10000.00}")
    private BigDecimal dailyLimit;

    @Value("${fraud.max-per-txn:50000.00}")
    private BigDecimal maxPerTxn;

    public void validate(String senderUpiId, String receiverUpiId, BigDecimal amount) {
        if (amount.compareTo(maxPerTxn) > 0) {
            throw new FraudVelocityException("AMOUNT_EXCEEDS_LIMIT", "Amount ₹" + amount + " exceeds per-transaction limit of ₹" + maxPerTxn);
        }
        if (senderUpiId.equals(receiverUpiId)) {
            throw new FraudVelocityException("SELF_PAYMENT", "Sender and receiver UPI ID must be different");
        }
        Instant startOfDay = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal dailySent = transactionRepository.sumSuccessfulAmountSince(senderUpiId, startOfDay);
        if (dailySent.add(amount).compareTo(dailyLimit) > 0) {
            throw new FraudVelocityException("DAILY_LIMIT_EXCEEDED", "Daily limit of ₹" + dailyLimit + " exceeded for " + senderUpiId);
        }
    }
}
