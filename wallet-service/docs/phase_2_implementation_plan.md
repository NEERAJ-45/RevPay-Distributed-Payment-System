# Phase 2 Implementation Plan: Wallet Service & Ledgers

This document outlines the step-by-step implementation plan for Phase 2 of the Mini-UPI project, focusing on completing the `wallet-service` module.

## 1. DTOs (Data Transfer Objects)
- **Create `UserCreatedEvent.java`**: 
  - **Location**: `com.neeraj.upi.wallet.dto`
  - **Purpose**: Mirror the `UserCreatedEvent` payload produced by `user-service` to correctly deserialize Kafka messages.
  - **Fields**: `UUID userId`, `String upiId`, `String fullName`, `String phone`, `Instant createdAt`.
- **Create `LedgerResponse.java`**:
  - **Location**: `com.neeraj.upi.wallet.dto`
  - **Purpose**: Return mapped `LedgerEntry` objects to the client.
  - **Fields**: Match relevant fields from `LedgerEntry` entity (id, walletId, transactionId, type, amount, balanceAfter, note, createdAt).

## 2. Entity & Repositories
- **Update `Wallet.java`**:
  - Add `@Version` field to safely update balances during concurrent transfers using Optimistic Locking.
- **Update `WalletRepository.java`**:
  - No custom locking query needed; rely on standard `findByUpiId` and JPA optimistic locking.

## 3. Service Layer (`WalletService.java`)
Replace the `UnsupportedOperationException` placeholders with actual implementations:

- **`createWallet(UUID userId, String upiId)`**:
  - **Idempotency**: Check if a wallet already exists using `walletRepository.existsByUserId(userId)`. If true, return early (or log and skip).
  - **Creation**: Build a new `Wallet` with `balance = 0.00`.
  - **Save**: Save it to the database using `walletRepository.save()`.

- **`getBalance(String upiId)`**:
  - Fetch the wallet using `walletRepository.findByUpiId(upiId)`.
  - Throw `WalletNotFoundException` if it doesn't exist.
  - Map the entity to `WalletResponse` and return.

- **`addMoney(String upiId, AddMoneyRequest req)`**:
  - Fetch wallet by `upiId` using `walletRepository.findByUpiId(upiId)`. Throw if not found.
  - Add `req.getAmount()` to the wallet's balance.
  - Create a new `LedgerEntry` of type `CREDIT` with the new balance and the `note` from the request.
  - Save the wallet and the ledger entry.
  - Return updated `WalletResponse`.

- **`transfer(TransferRequest req)`**:
  - **Sender Fetch**: Fetch sender wallet using `walletRepository.findByUpiId(req.getFromUpiId())` (Optimistic locking handles concurrent updates).
  - **Balance Check**: Throw `InsufficientFundsException` if sender balance < `req.getAmount()`.
  - **Debit**: Subtract amount from sender. Create a `DEBIT` `LedgerEntry`.
  - **Receiver Fetch**: Fetch receiver wallet using `walletRepository.findByUpiId(req.getToUpiId())`. (Optional: lock receiver if needed, but sender is strictly needed to prevent overdrafts).
  - **Credit**: Add amount to receiver. Create a `CREDIT` `LedgerEntry`.
  - **Save**: Save both wallets and both ledger entries within the same `@Transactional` boundary.

## 4. Kafka Listener (`UserCreatedListener.java`)
- Update `onUserCreated(Object event)` to accept `UserCreatedEvent`.
- Use the `@Payload` annotation correctly.
- Extract `userId` and `upiId` from the event and call `walletService.createWallet(...)`.
- Add proper logging.

## 5. Controller Layer (`WalletController.java`)
Replace the `UnsupportedOperationException` placeholders with actual calls to the service layer:

- **`getBalance`**: Call `walletService.getBalance(upiId)` and wrap in `ApiResponse.success()`.
- **`addMoney`**: Call `walletService.addMoney(upiId, request)` and wrap in `ApiResponse.success()`.
- **`transfer`**: Call `walletService.transfer(request)` and return `ApiResponse.success(null, "Transfer successful")`.
- **`getLedger`**:
  - Fetch wallet ID by `upiId`.
  - Create a `PageRequest` object.
  - Call `ledgerRepository.findByWalletIdOrderByCreatedAtDesc(...)`.
  - Map the results to `LedgerResponse` and return as an `ApiResponse`.

## 6. Testing & Validation
- **Run `WalletServiceApplication`** on port `8082`.
- Ensure a wallet is auto-created in the database when a new user registers in `user-service`.
- Test `addMoney` via Swagger/Postman and verify `ledger_entries` reflects the CREDIT.
- Test `transfer` by creating two accounts, adding money to one, and doing an internal transfer. Verify double-entry ledger bookkeeping.
