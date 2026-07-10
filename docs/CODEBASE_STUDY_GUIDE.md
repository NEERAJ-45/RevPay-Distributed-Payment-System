# RevPay Codebase Study Guide

## Project Overview

RevPay is a distributed UPI-style payment system with 5 microservices communicating via HTTP (Feign), Kafka (async events), and Redis (idempotency).

```
                    ┌──────────────┐
                    │  API Gateway  │  (Spring Cloud Gateway - JWT auth, rate limit)
                    └──────┬───────┘
                           │
              ┌────────────┼────────────────┐
              │            │                │
       ┌──────▼───┐  ┌────▼────┐  ┌───────▼──────┐
       │  User     │  │Transaction│  │   Wallet     │
       │  Service  │  │ Service   │  │   Service    │
       └──────┬────┘  └────┬────┘  └───────┬──────┘
              │            │                │
              └────────────┼────────────────┘
                           │
                    ┌──────▼───────┐
                    │ Notification  │
                    │   Service     │
                    └──────────────┘
```

**Patterns used:** Transactional Outbox, Idempotency Key, Optimistic Locking, Event-Driven, Saga (partial)

---

## Module Map

### 1. API Gateway (`api-gateway/`)
**Port:** 8080 (configurable)

| File | Purpose |
|------|---------|
| `GatewayConfig.java` | Rate limiter IP key resolver |
| `JwtAuthFilter.java` | Global JWT validation for all routes |
| `ObservabilityConfig.java` | Common metrics tags |

**Flow:** Client → Gateway → JWT check → Route to downstream service

---

### 2. User Service (`user-service/`)
**Port:** 8081

| File | Purpose |
|------|---------|
| `SecurityConfig.java` | Spring Security with JWT filter chain |
| `JwtAuthFilter.java` | Extracts JWT, sets SecurityContext |
| `JwtService.java` | Interface for token generation/validation |
| `ObservabilityConfig.java` | Metrics & tracing config |

**Flow:** Register → JWT issued → publishes `UserCreatedEvent` to Kafka

---

### 3. Wallet Service (`wallet-service/`)
**Port:** 8082

| File | Purpose |
|------|---------|
| `WalletController.java` | REST: balance, add-money, transfer, ledger |
| `WalletService.java` | Core logic: create, add, transfer with optimistic locking |
| `UserCreatedListener.java` | Kafka consumer: auto-create wallet on signup |
| `Wallet.java` | Entity with `@Version` for optimistic locking |
| `LedgerEntry.java` | Immutable audit trail (every debit/credit) |
| `ObservabilityConfig.java` | Metrics & tracing config |

**Key implementation:**
- `transfer()` — atomic debit+credit within `@Transactional`, locked via `@Version`
- `getLedger()` — paginated history mapped to `LedgerResponse` DTOs

---

### 4. Transaction Service (`transaction-service/`)
**Port:** 8083

| File | Purpose |
|------|---------|
| `PayController.java` | REST: pay, getById, getHistory |
| `TransactionService.java` | 6-step orchestrator: idempotency → PENDING → fraud → transfer → SUCCESS → outbox |
| `FraudEngine.java` | Daily limit, per-txn cap, self-pay detection |
| `IdempotencyService.java` | Redis-backed dedup (requestId → txnId, 24h TTL) |
| `OutboxScheduler.java` | Polls `outbox_events` table → publishes to Kafka |
| `TransactionEventPublisher.java` | Async + sync Kafka publish methods |
| `WalletFeignClient.java` | Internal Feign client to wallet-service |

**6-step payment flow:**
1. Idempotency check (Redis) — replay if already processed
2. Save PENDING transaction (DB)
3. Fraud validation (daily limit, amount cap, self-pay)
4. Wallet transfer via Feign (HTTP to wallet-service)
5. Mark SUCCESS, store idempotency key
6. Write outbox event → OutboxScheduler → Kafka → Notification service

---

### 5. Notification Service (`notification-service/`)
**Port:** 8084

| File | Purpose |
|------|---------|
| `TransactionEventListener.java` | Kafka: SUCCESS → debit+credit alerts, FAILED → failure alert |
| `UserCreatedListener.java` | Kafka: welcome SMS |
| `NotificationService.java` | Mock SMS logger (Twilio/SNS ready) |
| `ObservabilityConfig.java` | Metrics & tracing config |

---

## Data Flow: End-to-End Payment

```
Client → POST /transactions/pay
  │
  ├─ PayController.extractUpiId() ← JWT
  ├─ TransactionService.pay()
  │   ├─ 1. Redis: idempotency check
  │   ├─ 2. DB:   save PENDING transaction
  │   ├─ 3. FraudEngine.validate() — daily limit, amount, self-pay
  │   ├─ 4. Feign → WalletService.transfer() — debit/credit
  │   ├─ 5. DB:   mark SUCCESS + Redis: store idempotency key
  │   └─ 6. DB:   write OutboxEvent → polled by OutboxScheduler → Kafka
  │
  └─ Response returned (201 CREATED or 200 OK if replayed)

OutboxScheduler (every 2s):
  └─ Poll unprocessed outbox_events → publishSync() to Kafka → mark processed

Notification Service (Kafka):
  ├─ SUCCESS → sendDebitAlert() + sendCreditAlert()
  └─ FAILED  → sendFailureAlert()
```

---

## Key Implementation Details

### Idempotency (Redis)
- Client generates UUID `requestId`, sends with every payment
- Before processing: `GET idempotency:{requestId}` → if found, return cached response immediately
- After processing: `SET idempotency:{requestId} {txnId} EX 86400`
- Same requestId → same result, no double charge

### Optimistic Locking (@Version)
```java
// Wallet.java
@Version
private Long version;
```
- Every UPDATE includes `WHERE version = ? AND id = ?`
- Hibernate auto-increments version on write
- Second concurrent writer gets `OptimisticLockException`
- Prevents lost-update on wallet balance

### Transactional Outbox
- `TransactionService.pay()` writes `OutboxEvent` to `outbox_events` table (same DB transaction)
- `OutboxScheduler` polls every 2s: `SELECT * FROM outbox_events WHERE processed = false`
- For each event: deserialize payload → `publishSync()` (blocks on Kafka ack) → mark processed
- Guarantees at-least-once delivery without dual-write to Kafka

### Fraud Engine
```java
fraudEngine.validate(senderUpiId, receiverUpiId, amount);
// 1. amount <= maxPerTxn (default ₹50,000)
// 2. senderUpiId != receiverUpiId (no self-pay)
// 3. dailySent + amount <= dailyLimit (default ₹10,000)
```

---

## Testing Strategy

| Layer | Tool | Coverage |
|-------|------|----------|
| Controller | MockMvc | HTTP status, response envelope, route correctness |
| Service | Mockito + JUnit 5 | Business logic, idempotency, fraud, edge cases |
| Kafka Listener | Mockito | Event routing (SUCCESS vs FAILED) |

### Test Files
| Test | What it covers |
|------|----------------|
| `JwtAuthFilterTest` | WebTestClient: 401 on missing/invalid JWT |
| `PayControllerTest` | MockMvc: POST /transactions/pay, status codes |
| `TransactionServiceTest` | Pay success, idempotent replay, fraud failure |
| `WalletControllerTest` | Get balance, add money, transfer endpoints |
| `WalletServiceTest` | Create wallet (idempotent), add money (ledger), transfer (success + insufficient funds) |
| `TransactionEventListenerTest` | SUCCESS → debit+credit alerts, FAILED → failure alert |

---

## Infrastructure (Terraform)

| Module | Resource |
|--------|----------|
| `infra/aws/alb/` | ALB, target group, listeners (HTTP→HTTPS redirect), security groups |
| `infra/aws/sns/` | SNS topic + email subscription for alarm notifications |
| `infra/aws/cloudwatch/` | Log groups (5 services), 10 CloudWatch alarms (SEV1-3 + billing) |

---

## Configuration Reference

| Property | Default | Service |
|----------|---------|---------|
| `jwt.secret` | (required) | api-gateway, user-service, transaction-service |
| `wallet.service.url` | (required) | transaction-service |
| `fraud.daily-limit` | 10000.00 | transaction-service |
| `fraud.max-per-txn` | 50000.00 | transaction-service |
| `idempotency.ttl-seconds` | 86400 | transaction-service |

---

## Commands

```bash
# Build all services
mvn clean install -DskipTests

# Run locally
docker-compose up -d          # Postgres, Redis, Kafka
# Start each service via IDE or:
mvn spring-boot:run -pl api-gateway
mvn spring-boot:run -pl user-service
mvn spring-boot:run -pl wallet-service
mvn spring-boot:run -pl transaction-service
mvn spring-boot:run -pl notification-service

# Run tests
mvn test
```
