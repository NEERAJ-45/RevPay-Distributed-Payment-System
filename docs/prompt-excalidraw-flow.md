# Prompt: Generate Full Architecture Flow Diagram (Excalidraw)

Copy this prompt into any AI tool (ChatGPT, Claude, etc.) to generate the complete Excalidraw diagram for the RevPay system:

---

Generate an Excalidraw diagram JSON for the **RevPay Distributed Payment System** architecture. Use the following specifications:

## Style
- White/light background, blue/green accent colors
- Rounded rectangle containers for services (stroke width 2)
- Solid arrows for synchronous HTTP calls, dashed arrows for async Kafka events
- Label all arrows with method (POST/GET) and endpoint path
- Use `═══` box styling for databases, `◉` icon markers for Kafka topics
- Text labels in `fontSize: 16` for section headers, `fontSize: 14` for services, `fontSize: 12` for labels

## Layout (top-to-bottom, left-to-right)

**Layer 1 — External Client** (top center)
- Box: "Client (Mobile App / Browser)" → arrow to Nginx

**Layer 2 — Load Balancer** (below client)
- Box: "Nginx (:80) — Rate: 30 req/s"
- Arrow labeled `proxy_pass` to API Gateway

**Layer 3 — API Gateway** (below Nginx)
- Box: "API Gateway — Spring Cloud Gateway (:8080)"
- Inside list: "JwtAuthFilter (global)", "RequestRateLimiter (Redis)", "Route: /auth/** → :8081", "Route: /wallet/** → :8082", "Route: /transactions/** → :8083", "Route: /wallet/internal/** → BLOCKED"
- Three outgoing arrows to services below:
  - `POST/GET /auth/**, /users/**` → User Service
  - `POST/GET /wallet/**` → Wallet Service
  - `POST/GET /transactions/**` → Transaction Service

**Layer 4 — Microservices** (middle row, side by side)
- **User Service** (left): "User Service (:8081) — Spring Boot + JPA", sub-boxes: "DB: upi_users", "Tables: users, outbox_events"
- **Wallet Service** (center-left): "Wallet Service (:8082) — Spring Boot + JPA", sub-boxes: "DB: upi_wallets", "Tables: wallets (@Version), ledger_entries"
- **Transaction Service** (center-right): "Transaction Service (:8083) — Spring Boot + JPA + Redis", sub-boxes: "DB: upi_transactions", "Tables: transactions, outbox_events"
- **Notification Service** (right): "Notification Service (:8084) — Spring Boot"

**Layer 5 — Data Stores** (below services)
- **PostgreSQL** box: "PostgreSQL 16 (:5432)" with 3 sub-databases: "upi_users", "upi_wallets", "upi_transactions"
- **Redis** box: "Redis 7.2 (:6379)" with label: "Idempotency keys (TTL 24h)"

**Layer 6 — Async Layer** (bottom)
- **Kafka** central box: "Kafka 7.6 (:9092)" with topic containers inside:
  - Topic: "user.created" — arrows from User Service → Kafka → Wallet Service + Notification Service
  - Topic: "txn.completed" — arrows from Transaction Service → Kafka → Notification Service
  - Topic: "txn.failed" — arrows from Transaction Service → Kafka → Notification Service

## Arrows & Data Flows

### Synchronous Calls (solid arrows, blue #1971c2):
1. **Client → Nginx**: `HTTP :80` (solid #333)
2. **Nginx → API Gateway**: `proxy_pass` (solid #333)
3. **Gateway → User Service**: `POST /api/auth/register` (create user, return JWT + upiId), `POST /api/auth/login` (validate, return JWT), `GET /users/me` (profile), `GET /users/{upiId}` (lookup), `GET /users/qr/{upiId}` (QR code)
4. **Gateway → Wallet Service**: `GET /wallet/balance/{upiId}`, `POST /wallet/add-money/{upiId}`, `GET /wallet/ledger/{upiId}?page=&size=`
5. **Gateway → Transaction Service**: `POST /transactions/pay` with body `{requestId, toUpiId, amount, note?}`, `GET /transactions/{txnId}`, `GET /transactions/history/{upiId}?page=&size=`
6. **Transaction Service → Wallet Service** (Feign, bypasses gateway): `POST /wallet/internal/transfer` with body `{transactionId, fromUpiId, toUpiId, amount, note}`, `GET /wallet/balance/{upiId}`
7. **Transaction Service → Redis**: `GET/SET idempotency:<requestId>` (dashed arrow, #e67700)

### Async Events (dashed arrows, green #2f9e44):
1. **User Service → Kafka** (topic: `user.created`): Published by `OutboxScheduler` → `UserEventPublisher` every 2s. Event: `UserCreatedEvent{userId, upiId, fullName, phone, createdAt}`
2. **Kafka → Wallet Service** (consumer group: `wallet-service-group`): `UserCreatedListener.onUserCreated()` → `createWallet(upiId)` → wallet with balance=0
3. **Kafka → Notification Service** (consumer group: `notification-service-group`): `UserCreatedListener` → `sendWelcome()`
4. **Transaction Service → Kafka** (topic: `txn.completed`): Published by `OutboxScheduler` → `TransactionEventPublisher`. Event: `TransactionCompletedEvent{txnId, senderUpiId, receiverUpiId, amount, status=SUCCESS}`
5. **Transaction Service → Kafka** (topic: `txn.failed`): Same publisher, status=FAILED. Event includes `failureReason`
6. **Kafka → Notification Service**: `TransactionEventListener.onTransactionEvent()` → `sendDebitAlert()` + `sendCreditAlert()` (for completed), or `sendFailureAlert()` (for failed)

## Payment Flow Callout Box (annotated on the right side)
Create a numbered step callout box titled **"Payment Orchestration (6 Steps)"**:
```
STEP 1: JWT Extraction → get upiId from token
STEP 2: Idempotency Check → Redis GET <requestId>
STEP 3: Save PENDING → INSERT transactions (status=PENDING)
STEP 4: Fraud Engine → validate (amt≤₹50K, no self-pay, daily≤₹10K)
STEP 5: Wallet Transfer → Feign POST /wallet/internal/transfer (atomic debit+credit with @Version optimistic lock)
STEP 6: Finalize → UPDATE status=SUCCESS, Redis SET idempotency key, INSERT outbox_event
```

## Footer / Legend (bottom right)
- Solid blue arrow = Synchronous REST call
- Dashed green arrow = Async Kafka event
- Dashed orange arrow = Redis access
- Red lightning bolt = Fraud Engine validation point

Generate the complete Excalidraw JSON that I can import directly into `excalidraw.com`.
