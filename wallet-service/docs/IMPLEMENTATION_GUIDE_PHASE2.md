# 📘 Implementation Guide – Phase 2 (Wallet Service)

## 🎯 Goal
Deliver a production‑ready **Wallet Service** that supports:
- Automatic wallet creation via Kafka (`user.created`)
- Adding money to a wallet
- Secure, atomic transfers using optimistic locking
- Comprehensive tests and observability

---

## 📚 Prerequisites
| Item | Version |
|------|---------|
| Java | 17 |
| Spring Boot | 3.2.x |
| PostgreSQL | 15 |
| Kafka | 3.5.x |
| Maven | 3.9.x |

- Ensure the **wallet‑service** module builds: `./mvnw clean install`
- Verify connectivity to PostgreSQL and Kafka (use `docker-compose.yml` located in the root repo).

---

## 🏗️ Phase 2.1 – Domain Entities & Optimistic Locking
1. **Create entities** (`Wallet.java`, `LedgerEntry.java`) under `com.neeraj.upi.wallet.entity`.
   - Use `@Entity`, `@Id`, `@Version` for optimistic locking.
   - `balance` column: `@Column(precision = 19, scale = 4)`.
2. **Add repository interfaces** extending `JpaRepository`.
   - `WalletRepository` with `findByUpiId` & `findByUserId`.
   - `LedgerEntryRepository` with `findByWalletIdOrderByTimestampDesc`.
3. **Migrations** – add `V1__wallet_schema.sql` to `src/main/resources/db/migration`.
   ```sql
   CREATE TABLE wallet (
       id UUID PRIMARY KEY,
       user_id UUID NOT NULL,
       upi_id VARCHAR(255) UNIQUE NOT NULL,
       balance NUMERIC(19,4) NOT NULL,
       version BIGINT NOT NULL,
       created_at TIMESTAMP NOT NULL DEFAULT now()
   );

   CREATE TABLE ledger_entry (
       id UUID PRIMARY KEY,
       wallet_id UUID NOT NULL REFERENCES wallet(id),
       transaction_id UUID NOT NULL,
       amount NUMERIC(19,4) NOT NULL,
       type VARCHAR(10) NOT NULL,
       balance_after NUMERIC(19,4) NOT NULL,
       timestamp TIMESTAMP NOT NULL DEFAULT now()
   );
   ```

---

## 📡 Phase 2.2 – Kafka Consumer (Auto‑Wallet Creation)
1. **Listener** – `UserCreatedListener.java` in `com.neeraj.upi.wallet.listener`.
   ```java
   @KafkaListener(topics = "user.created", groupId = "wallet-service-group")
   public void onUserCreated(UserCreatedEvent event) {
       walletService.createWallet(event.getUserId(), event.getUpiId());
   }
   ```
2. **Service method** – `WalletService.createWallet`:
   - Idempotent check: `if (walletRepository.findByUpiId(upiId).isPresent()) return;`
   - Persist new `Wallet` with zero balance.
3. **Configuration** – add to `application.yml` (already present).
4. **Testing** – unit test that duplicate events do not create extra rows.

---

## 💰 Phase 2.3 – Add Money
1. **Service method** – `WalletService.addMoney(String upiId, BigDecimal amount)`:
   - Validate `amount > 0`.
   - Fetch wallet, update balance, save (optimistic lock handles concurrent adds).
   - Record a `LedgerEntry` of type `CREDIT` with `balanceAfter`.
2. **Controller** – `POST /wallet/add-money`.
   ```json
   { "upiId": "user@upi", "amount": 100.00 }
   ```
3. **Transactionality** – annotate method with `@Transactional`.
4. **Tests** – verify balance update and ledger entry creation.

---

## 🔄 Phase 2.4 – Transfer (Critical Path)
1. **Method signature**:
   ```java
   @Transactional(isolation = Isolation.READ_COMMITTED)
   public void transfer(String senderUpi, String receiverUpi, BigDecimal amount, UUID txnId)
   ```
2. **Flow**:
   - Load both wallets, validate existence and sufficient funds.
   - Perform balance arithmetic using `BigDecimal`.
   - Save both wallets; JPA will include `WHERE version = ?` ensuring optimistic lock.
   - Persist two `LedgerEntry` records (DEBIT for sender, CREDIT for receiver).
3. **Error handling**:
   - `InsufficientFundsException` → 400 response.
   - `OptimisticLockingFailureException` → propagate to Transaction Service (Feign client) where the transaction is marked `FAILED`.
4. **Internal endpoint** – `POST /internal/wallet/transfer` (exposed only to the Transaction Service).
5. **Testing checklist** – see end of DEV_PLAN for scenarios.

---

## ✅ Testing Checklist (Automated & Manual)
- [ ] **Wallet auto‑creation** – send `user.created` twice → single row.
- [ ] **Add‑money concurrency** – simulate parallel adds, ensure final balance matches sum.
- [ ] **Transfer success** – normal flow updates both balances and ledger.
- [ ] **Transfer failure** – insufficient funds returns proper error.
- [ ] **Optimistic lock retry** – induce version conflict and verify retry logic in Transaction Service.
- [ ] **Integration** – run full stack (`docker-compose up`) and hit API endpoints via Postman.

---

## 🔑 Key Design Decisions (Recap)
| Decision | Rationale |
|----------|-----------|
| `@Version` Optimistic Locking | ~10× throughput vs `SELECT FOR UPDATE` under high TPS |
| `BigDecimal(19,4)` | Financial precision; avoids rounding errors |
| Immutable `LedgerEntry` | Audit‑ability – append‑only log |
| Idempotent `createWallet()` | Kafka at‑least‑once delivery guarantees |
| Internal‑only transfer endpoint | Security – only trusted services may move funds |

---

## 📦 Deployment Checklist
1. Build Docker image: `docker build -t wallet-service:latest .`
2. Push to registry.
3. Update `docker-compose.yml` service image tag.
4. Apply DB migrations with Flyway (`./mvnw flyway:migrate`).
5. Verify health endpoint `/actuator/health` returns `UP`.

> **Next Steps** – After the guide is reviewed, start implementing the tasks in the order presented. Use the **Testing Checklist** to drive CI pipelines.
