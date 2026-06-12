package com.neeraj.upi.wallet.service;

import com.neeraj.upi.wallet.dto.AddMoneyRequest;
import com.neeraj.upi.wallet.dto.TransferRequest;
import com.neeraj.upi.wallet.dto.WalletResponse;
import com.neeraj.upi.wallet.repository.LedgerRepository;
import com.neeraj.upi.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final WalletRepository  walletRepository;
    private final LedgerRepository  ledgerRepository;

    /** Auto-creates a wallet with ₹0 balance when a new user registers */
    @Transactional
    public void createWallet(UUID userId, String upiId) {
        // TODO: check if wallet already exists for userId (idempotent)
        //       build Wallet entity, save, log
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /** Returns balance and wallet details for a given UPI ID */
    @Transactional(readOnly = true)
    public WalletResponse getBalance(String upiId) {
        // TODO: find wallet by upiId, map to WalletResponse
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /** Mocks a bank top-up — credits the wallet directly */
    @Transactional
    public WalletResponse addMoney(String upiId, AddMoneyRequest req) {
        // TODO: find wallet, add amount to balance, save LedgerEntry(CREDIT), return updated WalletResponse
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * INTERNAL ONLY — atomic debit sender + credit receiver in one transaction.
     * Called only by transaction-service via OpenFeign.
     * @throws InsufficientFundsException if sender balance < amount
     */
    @Transactional
    public void transfer(TransferRequest req) {
        // TODO:
        // 1. Find sender wallet by fromUpiId (optimistic locking will handle concurrency)
        // 2. Check sender.balance >= req.amount, else throw InsufficientFundsException
        // 3. Debit sender: balance -= amount, save LedgerEntry(DEBIT)
        // 4. Find receiver wallet by toUpiId
        // 5. Credit receiver: balance += amount, save LedgerEntry(CREDIT)
        // 6. Both saves happen in same @Transactional — either both commit or both rollback
        // 7. Record transaction metrics (e.g., wallet.transfers.count counter and wallet.transfers.amount summary)
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
