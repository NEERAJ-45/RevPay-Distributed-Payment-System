# RevPay Microservice Flow — Detailed Breakdown

> **Architecture:** 5 microservices | Spring Boot 3.3 | Java 21 | PostgreSQL | Redis | Kafka

---

## 1. User Service (port 8081)

**Purpose:** User lifecycle — registration, authentication, profile lookup, JWT issuance.

### Internal Structure

| Layer | Files |
|-------|-------|
| Controller | `AuthController.java`, `UserProfileController.java` |
| Service | `UserService.java`, `JwtService.java`, `QrCodeService.java`, `UpiIdGenerator.java` |
| Repository | `UserRepository.java`, `OutboxEventRepository.java` |
| Entity | `User.java`, `OutboxEvent.java` |
| DTO | `RegisterRequest`, `LoginRequest`, `AuthResponse`, `UserProfileResponse`, `QrCodeResponse` |
| Config | `SecurityConfig.java`, `JwtAuthFilter.java`, `ObservabilityConfig.java` |

### Endpoints

```
POST /api/auth/register   → 201 + JWT + upiId     (public)
POST /api/auth/login      → 200 + JWT + upiId     (public)
GET  /users/me            → 200 + profile          (JWT required)
GET  /users/{upiId}       → 200 + profile lookup   (JWT required)
GET  /users/qr/{upiId}    → 200 + QR code (Base64) (JWT required)
```

### Flow: Registration

```yaml
Request:  POST /api/auth/register
Body:     { "fullName": "Alice", "phone": "9876543210", "pin": "1234" }

Step 1: UserService.register()
  ├─ Check: phone not already registered
  ├─ Generate: upiId = "alice@miniupi"  (via UpiIdGenerator)
  ├─ Hash: pin with BCrypt (strength 12)
  ├─ Save: User entity to upi_users DB
  ├─ Outbox: write UserCreatedEvent to outbox_events table
  └─ Return: AuthResponse { token, upiId, fullName, tokenType }

Step 2: JwtService.generateToken()
  ├─ Claims: { sub=userId, upiId, phone }
  ├─ Sign: HMAC-SHA256 with jwt.secret
  └─ Return: signed JWT string

Step 3: OutboxScheduler (every 2s)
  └─ Poll outbox_events → publishSync to Kafka topic "user.created"

Step 4: Wallet Service consumes "user.created"
  └─ UserCreatedListener → walletService.createWallet(userId, upiId)
     └─ INSERT INTO wallets (user_id, upi_id, balance=0)

Step 5: Notification Service consumes "user.created"
  └─ UserCreatedListener → sendWelcome(upiId, name, phone)
     └─ [SMS] Welcome Alice! Your UPI ID is alice@miniupi
```

### JWT Structure

```json
HEADER:  { "alg": "HS256" }
PAYLOAD: {
  "sub": "uuid-of-user",
  "upiId": "alice@miniupi",
  "phone": "9876543210",
  "iat": 1710000000,
  "exp": 1710086400
}
```

---

## 2. Wallet Service (port 8082)

**Purpose:** Balance management, fund transfers, audit ledger. Uses optimistic locking.

### Internal Structure

| Layer | Files |
|-------|-------|
| Controller | `WalletController.java` |
| Service | `WalletService.java` |
| Repository | `WalletRepository.java`, `LedgerRepository.java` |
| Entity | `Wallet.java` (with `@Version`), `LedgerEntry.java` (immutable) |
| DTO | `WalletResponse`, `AddMoneyRequest`, `TransferRequest`, `LedgerResponse` |
| Config | `ObservabilityConfig.java` |

### Endpoints

```
GET  /wallet/balance/{upiId}       → balance + wallet details (JWT)
POST /wallet/add-money/{upiId}     → credit wallet, create CREDIT ledger entry (JWT)
POST /wallet/internal/transfer     → atomic debit/credit (INTERNAL — no JWT, from Feign)
GET  /wallet/ledger/{upiId}        → paginated transaction history (JWT)
```

### Flow: Get Balance

```yaml
Request:  GET /wallet/balance/alice@miniupi

Step 1: WalletController.getBalance()
  └─ walletService.getBalance("alice@miniupi")
     └─ walletRepository.findByUpiId("alice@miniupi")
        └─ Map to WalletResponse { id, userId, upiId, balance, createdAt }

Response: { "success": true, "data": { "upiId": "alice@miniupi", "balance": 5000.00 } }
```

### Flow: Add Money (Mock Bank Top-Up)

```yaml
Request:  POST /wallet/add-money/alice@miniupi
Body:     { "amount": 2000, "note": "Salary credit" }

Step 1: WalletService.addMoney()
  ├─ Find wallet by upiId
  ├─ balance = 3000 + 2000 = 5000
  ├─ Save wallet (Hibernate auto-increments @Version)
  ├─ LedgerEntry { type=CREDIT, amount=2000, balanceAfter=5000 }
  ├─ meterRegistry.counter("wallet.topups.count").increment()
  └─ Return WalletResponse

Ledger Table:
  id | wallet_id | type   | amount | balance_after | note
  ---+-----------+--------+--------+---------------+---------------
  u1 | w_uuid    | CREDIT | 2000   | 5000          | Salary credit
```

### Flow: Internal Transfer (Atomic Debit + Credit)

```yaml
Request:  POST /wallet/internal/transfer
Body:     { "transactionId": "...", "fromUpiId": "alice@miniupi",
            "toUpiId": "bob@miniupi", "amount": 200 }

Step 1: WalletService.transfer()  [@Transactional]
  ├─ Find sender wallet (SELECT ... FOR UPDATE via @Version)
  ├─ Check: sender.balance(5000) >= amount(200) ✓
  ├─ Debit: sender.balance = 5000 - 200 = 4800
  ├─ LedgerEntry { type=DEBIT, amount=200, balanceAfter=4800, transactionId=... }
  │
  ├─ Find receiver wallet
  ├─ Credit: receiver.balance = 1000 + 200 = 1200
  ├─ LedgerEntry { type=CREDIT, amount=200, balanceAfter=1200, transactionId=... }
  │
  ├─ meterRegistry.counter("wallet.transfers.count").increment()
  └─ Both saves commit OR rollback together

Optimistic Locking (@Version):
  UPDATE wallets SET balance=4800, version=2 WHERE id=uuid AND version=1
  └─ If another thread already updated, this throws OptimisticLockException → retry
```

---

## 3. Transaction Service (port 8083)

**Purpose:** Payment orchestration — idempotency, fraud, wallet coordination, outbox.

### Internal Structure

| Layer | Files |
|-------|-------|
| Controller | `PayController.java` |
| Service | `TransactionService.java`, `FraudEngine.java`, `IdempotencyService.java`, `OutboxScheduler.java` |
| Repository | `TransactionRepository.java`, `OutboxEventRepository.java` |
| Entity | `Transaction.java`, `OutboxEvent.java` |
| DTO | `PayRequest`, `PayResponse`, `TransferRequest` |
| Feign | `WalletFeignClient.java` |
| Kafka | `TransactionEventPublisher.java` |
| Config | `ObservabilityConfig.java` |

### Endpoints

```
POST /transactions/pay           → 201 (new) / 200 (replayed)    (JWT)
GET  /transactions/{txnId}       → transaction details           (JWT)
GET  /transactions/history/{upiId} → paginated history           (JWT)
```

### Flow: Payment — The 6-Step Orchestration

```yaml
Request:  POST /transactions/pay
Headers:  Authorization: Bearer <alice-jwt>
Body:     { "requestId": "550e8400-...", "toUpiId": "bob@miniupi", "amount": 200, "note": "Lunch" }

─── STEP 1: JWT Extraction ───
PayController.extractUpiId(authHeader)
  ├─ Parse JWT with HMAC key
  ├─ Extract claim "upiId" → "alice@miniupi"
  └─ This is the TRUSTED sender — never from request body

─── STEP 2: Idempotency Check ───
IdempotencyService.getExistingResult("550e8400-...")
  ├─ Redis GET idempotency:550e8400-...
  ├─ If found → return cached PayResponse immediately (replayed=true)
  │  └─ Fraud + wallet transfer SKIPPED. No double charge.
  └─ If null → first time, continue

─── STEP 3: Save PENDING Transaction ───
Transaction txn = Transaction.builder()
  .requestId("550e8400-...")
  .senderUpiId("alice@miniupi")
  .receiverUpiId("bob@miniupi")
  .amount(200)
  .status(PENDING)
  .build()
transactionRepository.save(txn)
  └─ INSERT INTO transactions (...) VALUES (...)

─── STEP 4: Fraud Engine ───
FraudEngine.validate("alice@miniupi", "bob@miniupi", 200)
  ├─ Rule 1: amount(200) <= maxPerTxn(50,000)? ✓
  ├─ Rule 2: sender != receiver? "alice" != "bob"? ✓
  ├─ Rule 3: today's total sent:
  │    SELECT COALESCE(SUM(amount),0) FROM transactions
  │    WHERE sender_upi_id='alice@miniupi'
  │      AND status='SUCCESS'
  │      AND created_at >= '2024-01-01 00:00:00Z'
  │    → let's say 1500 already sent
  │    → 1500 + 200 = 1700 <= 10,000(dailyLimit)? ✓
  └─ If any fails → FraudVelocityException → txn FAILED → outbox FAILED event

─── STEP 5: Wallet Transfer ───
walletFeignClient.transfer(transferReq)
  ├─ HTTP POST to wallet-service:/wallet/internal/transfer
  │  { "transactionId": "...", "fromUpiId": "alice@miniupi",
  │    "toUpiId": "bob@miniupi", "amount": 200 }
  ├─ WalletService.transfer() → atomic debit alice / credit bob
  └─ On success → continue. On failure (InsufficientFundsException) → catch block

─── STEP 6a: Mark SUCCESS ───
txn.setStatus(SUCCESS)
transactionRepository.save(txn)
idempotencyService.storeResult("550e8400-...", txnId)
  └─ Redis SET idempotency:550e8400-... <txnId> EX 86400

─── STEP 6b: Write Outbox Event ───
OutboxEvent { aggregateId=txnId, eventType="TRANSACTION_SUCCESS",
              payload={"txnId":"...","status":"SUCCESS",...}, processed=false }
outboxEventRepository.save(outbox)

─── ASYNC: OutboxScheduler (every 2s) ───
SELECT * FROM outbox_events WHERE processed=false ORDER BY created_at ASC
For each:
  ├─ Deserialize JSON → TransactionCompletedEvent
  ├─ kafkaTemplate.send("txn.completed", key, event).get()  ← blocks for ack
  ├─ UPDATE outbox_events SET processed=true WHERE id=id
  └─ If Kafka unavailable → stays unprocessed → retries next cycle

─── ASYNC: Notification Service ───
TransactionEventListener receives from "txn.completed":
  ├─ status == "SUCCESS"?
  │  ├─ sendDebitAlert(senderPhone, alice@miniupi, 200, txnId)
  │  │  [SMS] ₹200 debited from alice@miniupi. Txn Ref: <uuid>
  │  └─ sendCreditAlert(receiverPhone, bob@miniupi, 200, txnId)
  │     [SMS] ₹200 credited to bob@miniupi. Txn Ref: <uuid>
  └─ status == "FAILED"?
     └─ sendFailureAlert(senderPhone, 200, "Insufficient balance")
        [SMS] Payment of ₹200 failed. Reason: Insufficient balance
```

### Idempotency Replay Scenario

```yaml
Client retries with SAME requestId (network timeout, no response received):

  POST /transactions/pay { "requestId": "550e8400-...", "toUpiId": "bob@miniupi", "amount": 200 }

Step 2: IdempotencyService.getExistingResult("550e8400-...")
  ├─ Redis GET idempotency:550e8400-... → returns cached txnId
  ├─ transactionRepository.findById(txnId) → find existing SUCCESS txn
  └─ Return PayResponse { ..., replayed=true }

Result: 200 OK (not 201), same txnId. No money moved — safe replay.
```

### Fraud Engine Rules

| Rule | Condition | Violation Code |
|------|-----------|----------------|
| Per-txn cap | amount <= ₹50,000 (configurable) | `AMOUNT_EXCEEDS_LIMIT` |
| Self-pay | senderUpiId != receiverUpiId | `SELF_PAYMENT` |
| Daily limit | total sent today + amount <= ₹10,000 | `DAILY_LIMIT_EXCEEDED` |

---

## 4. API Gateway (port 8080)

**Purpose:** Single entry point — JWT validation, rate limiting, routing.

### Internal Structure

| Layer | Files |
|-------|-------|
| Config | `GatewayConfig.java` (IP key resolver), `ObservabilityConfig.java` |
| Filter | `JwtAuthFilter.java` (global JWT check) |

### Route Table

| Path | Target | Rate Limit | Auth |
|------|--------|------------|------|
| `/auth/**`, `/users/**` | user-service:8081 | 20/s burst 40 | Public (auth) / JWT (users) |
| `/wallet/**` (except `/internal/**`) | wallet-service:8082 | 20/s burst 40 | JWT |
| `/transactions/**` | transaction-service:8083 | 10/s burst 20 | JWT |
| `/wallet/internal/**` | **BLOCKED** by Gateway | — | — |

### Gateway Request Flow

```yaml
Request:  POST /transactions/pay

1. Route matches "transaction-service" predicate
2. JwtAuthFilter runs (Ordered.HIGHEST_PRECEDENCE = -1):
   ├─ Path starts with /auth/ → skip JWT check (public)
   ├─ Otherwise:
   │  ├─ Extract Authorization header
   │  ├─ Parse JWT with Keys.hmacShaKeyFor(jwtSecret)
   │  ├─ If invalid/missing → 401 UNAUTHORIZED
   │  └─ If valid → store {userId, upiId} in exchange attributes
   └─ Pass to downstream
3. RequestRateLimiter filter:
   ├─ KeyResolver returns client IP (or userId in future)
   ├─ Redis-based token bucket (replenishRate/burstCapacity)
   └─ If rate exceeded → 429 TOO_MANY_REQUESTS
4. Route to transaction-service:8083/transactions/pay
```

---

## 5. Notification Service (port 8084)

**Purpose:** Kafka consumer — sends SMS/email alerts for transactions and user events.

### Internal Structure

| Layer | Files |
|-------|-------|
| Kafka | `TransactionEventListener.java`, `UserCreatedListener.java` |
| Service | `NotificationService.java` (mock — logs to console) |
| Config | `ObservabilityConfig.java` |

### Kafka Topics

| Topic | Publisher | Consumer | Purpose |
|-------|-----------|----------|---------|
| `user.created` | User Service | Wallet Service, Notification Service | Auto-create wallet + welcome SMS |
| `txn.completed` | Transaction Service | Notification Service | Debit/credit alerts |
| `txn.failed` | Transaction Service | Notification Service | Failure alerts |

---

## Database Schema

### PostgreSQL — 3 Databases

```
upi_users (user-service)              upi_wallets (wallet-service)
┌──────────────────────────┐          ┌──────────────────────────┐
│ users                    │          │ wallets                  │
│──────────────────────────│          │──────────────────────────│
│ id (UUID, PK)            │          │ id (UUID, PK)            │
│ full_name (VARCHAR)      │          │ user_id (UUID, UNIQUE)   │
│ phone (VARCHAR, UNIQUE)  │          │ upi_id (VARCHAR)         │
│ email (VARCHAR)          │          │ balance (DECIMAL)        │
│ upi_id (VARCHAR, UNIQUE) │          │ version (BIGINT) @VERSION│
│ pin_hash (VARCHAR)       │          │ created_at (TIMESTAMP)   │
│ created_at (TIMESTAMP)   │          └──────────────────────────┘
│ updated_at (TIMESTAMP)   │
└──────────────────────────┘          ┌──────────────────────────┐
                                      │ ledger_entries           │
┌──────────────────────────┐          │──────────────────────────│
│ outbox_events            │          │ id (UUID, PK)            │
│──────────────────────────│          │ wallet_id (UUID)         │
│ id (UUID, PK)            │          │ transaction_id (UUID)    │
│ aggregate_id (VARCHAR)   │          │ type (CREDIT/DEBIT)      │
│ event_type (VARCHAR)     │          │ amount (DECIMAL)         │
│ payload (TEXT)           │          │ balance_after (DECIMAL)  │
│ processed (BOOLEAN)      │          │ note (VARCHAR)           │
│ created_at (TIMESTAMP)   │          │ created_at (TIMESTAMP)   │
│ processed_at (TIMESTAMP) │          └──────────────────────────┘
└──────────────────────────┘

upi_transactions (transaction-service)
┌──────────────────────────┐          ┌──────────────────────────┐
│ transactions             │          │ outbox_events            │
│──────────────────────────│          │──────────────────────────│
│ id (UUID, PK)            │          │ (same structure as       │
│ request_id (VARCHAR, UQ) │          │  user-service outbox)    │
│ sender_upi_id (VARCHAR)  │          └──────────────────────────┘
│ receiver_upi_id (VARCHAR)│
│ amount (DECIMAL)         │
│ note (VARCHAR)           │
│ status (PENDING/SUCCESS/ │
│         FAILED)          │
│ failure_reason (VARCHAR) │
│ created_at (TIMESTAMP)   │
│ updated_at (TIMESTAMP)   │
└──────────────────────────┘
```

---

## Infrastructure (AWS)

```yaml
VPC:    10.0.0.0/16
  ├─ Public subnets:  10.0.1.0/24, 10.0.2.0/24  (ALB + NAT)
  ├─ Private subnets: 10.0.10.0/24, 10.0.11.0/24 (EC2, RDS, ElastiCache)
  ├─ IGW → Public RT
  └─ NAT → Private RT

EC2:    1 × t2.micro (Amazon Linux 2023, 30GB gp3)
  ├─ Docker + Docker Compose
  ├─ CloudWatch Agent → /revpay/{env}/ec2-host
  └─ All 5 services via docker-compose.yml

RDS:    1 × db.t4g.micro (PostgreSQL 16, 20GB gp3, encrypted)
  ├─ DB: upi_users, upi_wallets, upi_transactions
  └─ Automated backups (7 days)

ElastiCache: 1 × cache.t4g.micro (Redis 7, encrypted, auth token)
  ├─ Idempotency key store
  └─ Rate limiter backing

ALB:    Application Load Balancer (free tier)
  ├─ Target group: api-gateway:8080
  ├─ Health check: /actuator/health
  ├─ HTTPS listener (if ACM cert provided)
  └─ HTTP → HTTPS redirect

Monitoring:
  ├─ CloudWatch Logs (6 log groups, 7 day retention)
  ├─ CloudWatch Alarms (10 alarms, SEV1-3 + billing)
  └─ SNS email subscription
```

---

## Complete Sequence Diagram (Text)

```
Alice                   Gateway           User Svc        Kafka          Wallet Svc     Transaction    Bob
 │                        │                │                │               │              │           │
 │ 1. POST /auth/register │                │                │               │              │           │
 │───────────────────────►│───────────────►│                │               │              │           │
 │                        │                │──Save User───►│               │              │           │
 │                        │                │──Pub user────►│──────────────►│              │           │
 │  ◄────JWT + upiId──────│◄───────────────│                │               │              │           │
 │                        │                │                │   Create Wallet              │           │
 │                        │                │                │◄──────────────│              │           │
 │ 2. POST /wallet/add-money/alice         │                │               │              │           │
 │───────────────────────►│(JWT check) ───►│                                               │           │
 │                        │                │──Add ₹5000───►(Wallet balance: 0→5000)         │           │
 │  ◄────bal: 5000────────│◄───────────────│                                               │           │
 │                        │                │                                               │           │
 │ 3. POST /transactions/pay {200, to:bob} │                │               │              │           │
 │───────────────────────►│(JWT check) ───►│──────────────────────────────────────────────►│           │
 │                        │                │                │               │              │           │
 │                        │                │                │   6-Step Orchestration:       │           │
 │                        │                │                │   ├─ 1. Redis idempotency ✓   │           │
 │                        │                │                │   ├─ 2. Save PENDING txn ✓    │           │
 │                        │                │                │   ├─ 3. Fraud check ✓         │           │
 │                        │                │                │   ├─ 4. Feign transfer ──────►│           │
 │                        │                │                │   │  └─ Debit alice ────────►(Alice)      │
 │                        │                │                │   │  └─ Credit bob ──────────►(Bob)       │
 │                        │                │                │   ├─ 5. Mark SUCCESS ✓        │           │
 │                        │                │                │   ├─ 6. Store idempotency ✓   │           │
 │                        │                │                │   └─ 7. Write outbox ✓        │           │
 │                        │                │                │                               │           │
 │  ◄──201 CREATED────────│◄───────────────│◄──────────────────────────────────────────────│           │
 │                        │                │                │                               │           │
 │                        │                │         OutboxScheduler polls every 2s         │           │
 │                        │                │                │◄──────────────│               │           │
 │                        │                │                │──Pub "txn.completed" ─────────│           │
 │                        │                │                │                               │           │
 │                        │                │                │  Notification Service         │           │
 │                        │                │                │  ├─ "[SMS] ₹200 debited"      │           │
 │                        │                │                │  └─ "[SMS] ₹200 credited"     │           │
```

---

## Metrics & Monitoring

| Metric | Type | Tags | Service |
|--------|------|------|---------|
| `upi.payments.count` | Counter | status=[SUCCESS,FAILED,REPLAYED] | transaction-service |
| `upi.payments.latency` | Timer | status=[SUCCESS,FAILED,REPLAYED] | transaction-service |
| `wallet.creations.count` | Counter | — | wallet-service |
| `wallet.topups.count` | Counter | — | wallet-service |
| `wallet.transfers.count` | Counter | — | wallet-service |
| `wallet.transfers.amount` | Counter | currency=INR | wallet-service |
