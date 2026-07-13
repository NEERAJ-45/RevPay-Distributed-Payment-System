package com.neeraj.upi.wallet.service;

import com.neeraj.upi.wallet.dto.AddMoneyRequest;
import com.neeraj.upi.wallet.dto.LedgerResponse;
import com.neeraj.upi.wallet.dto.TransferRequest;
import com.neeraj.upi.wallet.dto.WalletResponse;
import com.neeraj.upi.wallet.entity.LedgerEntry;
import com.neeraj.upi.wallet.entity.Wallet;
import com.neeraj.upi.wallet.exception.InsufficientFundsException;
import com.neeraj.upi.wallet.repository.LedgerRepository;
import com.neeraj.upi.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final WalletRepository  walletRepository;
    private final LedgerRepository  ledgerRepository;
    private final MeterRegistry meterRegistry;

    @Transactional
    public void createWallet(UUID userId, String upiId) {
        if (walletRepository.existsByUserId(userId)) {
            log.info("Wallet already exists for userId={}, skipping creation", userId);
            return;
        }
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .upiId(upiId)
                .balance(java.math.BigDecimal.ZERO)
                .build();
        walletRepository.save(wallet);
        log.info("Created wallet for userId={}, upiId={}", userId, upiId);
        meterRegistry.counter("wallet.creations.count").increment();
    }

    @Transactional(readOnly = true)
    public WalletResponse getBalance(String upiId) {
        Wallet wallet = walletRepository.findByUpiId(upiId)
                .orElseThrow(() -> new RuntimeException("Wallet not found for upiId: " + upiId));
        return WalletResponse.builder()
                .id(wallet.getId())
                .userId(wallet.getUserId())
                .upiId(wallet.getUpiId())
                .balance(wallet.getBalance())
                .createdAt(wallet.getCreatedAt())
                .build();
    }

    @Transactional
    public WalletResponse addMoney(String upiId, AddMoneyRequest req) {
        Wallet wallet = walletRepository.findByUpiId(upiId)
                .orElseThrow(() -> new RuntimeException("Wallet not found for upiId: " + upiId));
        wallet.setBalance(wallet.getBalance().add(req.getAmount()));
        walletRepository.save(wallet);

        LedgerEntry entry = LedgerEntry.builder()
                .walletId(wallet.getId())
                .type(LedgerEntry.EntryType.CREDIT)
                .amount(req.getAmount())
                .balanceAfter(wallet.getBalance())
                .note(req.getNote())
                .build();
        ledgerRepository.save(entry);

        log.info("Credited ₹{} to upiId={}, new balance=₹{}", req.getAmount(), upiId, wallet.getBalance());
        meterRegistry.counter("wallet.topups.count").increment();
        return getBalance(upiId);
    }

    @Transactional
    public void transfer(TransferRequest req) {
        Wallet sender = walletRepository.findByUpiId(req.getFromUpiId())
                .orElseThrow(() -> new RuntimeException("Sender wallet not found: " + req.getFromUpiId()));

        if (sender.getBalance().compareTo(req.getAmount()) < 0) {
            throw new InsufficientFundsException(req.getFromUpiId(), req.getAmount().toString());
        }

        sender.setBalance(sender.getBalance().subtract(req.getAmount()));
        walletRepository.save(sender);

        LedgerEntry debitEntry = LedgerEntry.builder()
                .walletId(sender.getId())
                .transactionId(req.getTransactionId())
                .type(LedgerEntry.EntryType.DEBIT)
                .amount(req.getAmount())
                .balanceAfter(sender.getBalance())
                .note(req.getNote())
                .build();
        ledgerRepository.save(debitEntry);

        Wallet receiver = walletRepository.findByUpiId(req.getToUpiId())
                .orElseThrow(() -> new RuntimeException("Receiver wallet not found: " + req.getToUpiId()));

        receiver.setBalance(receiver.getBalance().add(req.getAmount()));
        walletRepository.save(receiver);

        LedgerEntry creditEntry = LedgerEntry.builder()
                .walletId(receiver.getId())
                .transactionId(req.getTransactionId())
                .type(LedgerEntry.EntryType.CREDIT)
                .amount(req.getAmount())
                .balanceAfter(receiver.getBalance())
                .note(req.getNote())
                .build();
        ledgerRepository.save(creditEntry);

        meterRegistry.counter("wallet.transfers.count").increment();
        meterRegistry.counter("wallet.transfers.amount", "currency", "INR").increment(req.getAmount().longValue());
        log.info("Transferred ₹{} from {} to {}", req.getAmount(), req.getFromUpiId(), req.getToUpiId());
    }

    @Transactional(readOnly = true)
    public Page<LedgerResponse> getLedger(String upiId, int page, int size) {
        Wallet wallet = walletRepository.findByUpiId(upiId)
                .orElseThrow(() -> new RuntimeException("Wallet not found for upiId: " + upiId));
        Page<LedgerEntry> entries = ledgerRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), PageRequest.of(page, size));
        return entries.map(entry -> {
            LedgerResponse resp = new LedgerResponse();
            resp.setId(entry.getId());
            resp.setWalletId(entry.getWalletId());
            resp.setTransactionId(entry.getTransactionId());
            resp.setType(LedgerResponse.EntryType.valueOf(entry.getType().name()));
            resp.setAmount(entry.getAmount());
            resp.setBalanceAfter(entry.getBalanceAfter());
            resp.setNote(entry.getNote());
            resp.setCreatedAt(entry.getCreatedAt());
            return resp;
        });
    }
}
