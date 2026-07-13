# RevPay — Interview-Ready Deep Dive

## How to use this document
Each section has three layers:
1. **Happy Path** — standard flow, what you'd describe first in an interview
2. **Edge Cases & Failure Scenarios** — what breaks and how the system handles it
3. **Design Decisions & Trade-offs** — why this way, what were the alternatives

---

# FLOW 1: User Registration

## 1.1 Happy Path

```
Client                     User Service                    PostgreSQL               Kafka
  │                             │                             │                     │
  │  POST /api/auth/register    │                             │                     │
  │  {fullName, phone, pin}     │                             │                     │
  │────────────────────────────►│                             │                     │
  │                             │                             │                     │
  │                    1. Validate input                      │                     │
  │                       └─ phone matches ^[6-9]\d{9}$       │                     │
  │                       └─ pin is 4-6 digits                │                     │
  │                             │                             │                     │
  │                    2. Check uniqueness                    │                     │
  │                       └─ SELECT by phone                  │                     │
  │                       └─ If exists → 409 CONFLICT         │                     │
  │                             │                             │                     │
  │                    3. Generate upiId                      │                     │
  │                       └─ UpiIdGenerator:                  │                     │
  │                          sanitize(fullName).toLowerCase() │                     │
  │                          + "@miniupi"                     │                     │
  │                          └─ If taken → append number      │                     │
  │                             "neeraj1@miniupi"             │                     │
  │                             │                             │                     │
  │                    4. Hash PIN                            │                     │
  │                       └─ BCryptPasswordEncoder(12)        │                     │
  │                       └─ strength 12 ≈ 4-5 sec per hash   │                     │
  │                          (intentionally slow vs brute force)│                    │
  │                             │                             │                     │
  │                    5. Save User                           │                     │
  │                       └─ INSERT INTO users                │────► save OK        │
  │                             │                             │                     │
  │                    6. Save OutboxEvent                    │                     │
  │                       └─ INSERT INTO outbox_events        │────► save OK        │
  │                          {eventType: "USER_CREATED",      │                     │
  │                           processed: false}               │                     │
  │                             │                             │                     │
  │                    7. Generate JWT                        │                     │
  │                       └─ Jwts.builder()                   │                     │
  │                          .subject(userId.toString())      │                     │
  │                          .claim("upiId", upiId)           │                     │
  │                          .claim("phone", phone)           │                     │
  │                          .issuedAt(now)                   │                     │
  │                          .expiration(now + 24h)           │                     │
  │                          .signWith(secretKey)             │                     │
  │                          .compact()                       │                     │
  │                             │                             │                     │
  │  ◄──── 201 + JWT + upiId ──│                             │                     │
```

### Code References

| Step | File | Method |
|------|------|--------|
| Input validation | `RegisterRequest.java:13-29` | Jakarta `@NotBlank`, `@Pattern` |
| Uniqueness check | `UserService.java` | `userRepository.findByPhone()` |
| UPI generation | `UpiIdGenerator.java` | `generate(String fullName)` |
| Password hashing | `SecurityConfig.java:37` | `BCryptPasswordEncoder(12)` |
| User save | `UserService.java` | `userRepository.save(user)` |
| Outbox write | `UserService.java` | `outboxEventRepository.save(outbox)` |
| JWT creation | `JwtService.java` | `generateToken(UUID userId, String upiId, String phone)` |

---

## 1.2 Edge Cases & Failure Scenarios

### Scenario A: Duplicate Phone Number

```
Input: phone = "9876543210" (already registered)

Step 2: UserRepository.findByPhone("9876543210") → returns existing User
Action: throw RuntimeException → GlobalExceptionHandler → 409 CONFLICT
Response: { "success": false, "error": { "code": "PHONE_ALREADY_EXISTS", ... } }

Client should tell user: "This phone is already registered. Try logging in."
```

### Scenario B: Duplicate UPI ID

```
UpiIdGenerator generates "alice@miniupi" but it's taken.

Solution 1 (current): Generator checks DB, appends counter → "alice2@miniupi"
Solution 2 (better): Append short random suffix → "alice@miniupi" + UUID.random().substr(0,4)

Why current approach works: UPI ID has a UNIQUE constraint in DB → last resort catch.
```

### Scenario C: BCrypt is Slow (4-5 seconds)

```
Problem: BCrypt with strength 12 takes 4-5 seconds on the CPU.
This means the registration endpoint is inherently slow.

Why we accept this:
1. Registration is a rare operation (once per user lifetime)
2. BCrypt slowness IS the security — makes brute-force infeasible
3. Login also uses BCrypt, but that's fine since login is also infrequent

Trade-off: User experience vs Security
→ If UX is priority: lower strength to 10 (200ms), add rate limiting
→ If security is priority: strength 12, accept the delay
```

### Scenario D: User Save Succeeds, Outbox Write Fails

```
Timeline:
  1. INSERT INTO users → OK ✓
  2. INSERT INTO outbox_events → DB constraint violation ✗

What happens:
  └─ @Transactional annotates the entire method
  └─ Step 1 ROLLS BACK → user is NOT created
  └─ Client receives 500 error
  └─ Client retries → idempotent (phone is unique → 409, not duplicate)

This is CORRECT behavior. The outbox pattern guarantees:
  "Either both the entity and the outbox event are persisted,
   or neither is. Never one without the other."
```

### Scenario E: Kafka is Down After Registration

```
User created, outbox event written (processed=false).
OutboxScheduler tries to publish:
  1. Read unprocessed events
  2. kafkaTemplate.send().get() → Connection refused
  3. Exception caught, NOT marked as processed
  4. Next poll cycle (2s later) → retries

Recovery: When Kafka comes back up:
  └─ Event gets published
  └─ Wallet is created (eventual consistency)
  └─ Welcome SMS is sent (maybe 2 minutes late, but guaranteed)

Interview Answer: "The outbox pattern gives us at-least-once delivery
  even when Kafka is down. The consumer must be idempotent"
  (which wallet creation already is — it checks existsByUserId first).
```

---

## 1.3 Design Decisions

### Why Outbox Pattern instead of Dual-Write?

```
The naive approach (anti-pattern):
  userRepository.save(user)
  kafkaTemplate.send("user.created", event)  ← if this fails, user saved but no event

This is a DUAL-WRITE problem: two independent systems that can't atomically commit.

The outbox pattern solves this:
  userRepository.save(user)
  outboxEventRepository.save(outboxEvent)      ← same DB transaction!
  └─ OutboxScheduler polls → publishes to Kafka
  └─ Only marks processed AFTER Kafka confirms (publishSync)

Both writes are in the SAME database transaction — they succeed or fail together.
```

### Why BCrypt(12) instead of simpler hashing?

| Hash Algorithm | Time (approx) | Brute-force 8-char password |
|----------------|---------------|------------------------------|
| MD5 | < 1ms | ~1 hour |
| SHA-256 | < 1ms | ~2 hours |
| BCrypt(10) | ~200ms | ~200 years |
| BCrypt(12) | ~4-5s | ~200,000 years |

PINs are only 4-6 digits (10,000 to 1,000,000 combinations). Without BCrypt slowness, they'd be cracked instantly.

### Why separate DB per service?

```
Database-per-service pattern (NOT shared database):

  Service A → upi_users DB
  Service B → upi_wallets DB
  Service C → upi_transactions DB

Benefits:
  ├─ Loose coupling — schema changes don't cascade
  ├─ Independent scaling — wallet DB can be 10x larger
  ├─ Each service owns its data — no merge conflicts
  └─ Isolation — transaction-service outage doesn't affect user-service

Drawback:
  └─ Cross-service queries need API calls (no JOIN across services)
     Example: "get all transactions for user X" → call user-service to get upiId,
     then call transaction-service. Two network hops, eventual consistency.
```

---

# FLOW 2: Wallet Operations

## 2.1 Add Money (Mock Bank Top-Up)

### Happy Path

```
POST /wallet/add-money/alice@miniupi
Body: { "amount": 5000, "note": "Salary" }

Step 1: WalletService.addMoney()
  ├─ Wallet wallet = walletRepository.findByUpiId("alice@miniupi")
  │    └─ Returns: { id=uuid, balance=0, version=1 }
  │
  ├─ wallet.setBalance(BigDecimal.ZERO.add(BigDecimal.valueOf(5000)))
  │    └─ balance = 5000
  │
  ├─ walletRepository.save(wallet)
  │    └─ Hibernate generates:
  │       UPDATE wallets SET balance=5000.00, version=2
  │       WHERE id=uuid AND version=1
  │
  ├─ LedgerEntry entry = LedgerEntry.builder()
  │    .walletId(wallet.getId())
  │    .type(CREDIT)
  │    .amount(5000)
  │    .balanceAfter(5000)
  │    .note("Salary")
  │    .build()
  │
  ├─ ledgerRepository.save(entry)
  │
  └─ Return WalletResponse { balance: 5000 }
```

### Why LedgerEntry is Immutable

```java
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id private UUID id;        // set once
    @CreationTimestamp
    private Instant createdAt;   // set once by DB
    // NO @UpdateTimestamp — entries never change
}
```

Ledger entries are **append-only**. You can never UPDATE or DELETE a ledger row. This gives:
1. **Audit trail** — every rupee movement is permanently recorded
2. **Reconciliation** — sum of all entries must equal wallet balance
3. **Fraud detection** — you can replay the entire history
4. **Regulatory compliance** — financial systems require immutable audit logs

### Edge Case: Concurrent Add-Money Calls

```
Thread A: read balance=0, set balance=5000, save (version=1→2)
Thread B: read balance=0, set balance=3000, save (version=1→???)

What happens:
Thread B's save generates:
  UPDATE wallets SET balance=3000, version=2 WHERE id=uuid AND version=1
  └─ Thread A already updated version to 2!
  └─ Hibernate throws OptimisticLockException
  └─ Spring retries (if @Retryable configured) or propagates 409

Result: Thread B's money is NOT lost. The operation is retried.
After retry, Thread B reads balance=5000, sets 8000, saves correctly.

Without @Version:
  └─ Thread B's save quietly OVERWRITES Thread A's update
  └─ ₹5000 lost into thin air
  └─ UPI customer complains, you're in the news
```

---

## 2.2 Internal Transfer (Debit + Credit)

### Happy Path — The Atomic Transfer

```java
@Transactional
public void transfer(TransferRequest req) {
    // 1. Lock sender
    Wallet sender = walletRepository.findByUpiId(req.getFromUpiId());
    //    Hibernate loads sender with current @Version

    // 2. Check balance
    if (sender.getBalance().compareTo(req.getAmount()) < 0)
        throw new InsufficientFundsException(...);

    // 3. Debit sender
    sender.setBalance(sender.getBalance().subtract(req.getAmount()));
    walletRepository.save(sender);
    //    UPDATE wallets SET balance=4800, version=2 WHERE id=uuid AND version=1

    // 4. Lock receiver
    Wallet receiver = walletRepository.findByUpiId(req.getToUpiId());

    // 5. Credit receiver
    receiver.setBalance(receiver.getBalance().add(req.getAmount()));
    walletRepository.save(receiver);
    //    UPDATE wallets SET balance=1200, version=2 WHERE id=uuid AND version=1

    // 6. Both ledger entries (immutable, append-only)
    ledgerRepository.save(debitEntry);
    ledgerRepository.save(creditEntry);
}
//  Either ALL 6 steps succeed, or ALL are rolled back.
```

### Failure: Insufficient Funds

```
Alice balance = 100, tries to send 200.

Step 2: sender.getBalance() = 100
  100.compareTo(200) = -1 (less than)
  → throw InsufficientFundsException("INSUFFICIENT_FUNDS",
       "Wallet alice@miniupi has insufficient balance for ₹200")

@Transactional rolls back everything — no partial state.

Downstream (Transaction Service) catches this:
  └─ Mark transaction FAILED
  └─ Write outbox event with status FAILED
  └─ Notification: [SMS] Payment failed. Reason: insufficient funds
```

### Failure: Concurrent Transfer (Race Condition)

```
Alice balance = 500. Two transfers happen at EXACTLY the same time:
  Transfer A:  send ₹400 to Bob
  Transfer B:  send ₹300 to Charlie

Thread A:                                    Thread B:
1. Read balance=500 (version=1)             1. Read balance=500 (version=1)
2. Check 500 >= 400 ✓                       2. Check 500 >= 300 ✓
3. Set balance=100                          3. Set balance=200
4. UPDATE ... version=2 WHERE version=1     4. UPDATE ... version=2 WHERE version=1
   → SUCCESS (rows affected = 1)               → FAILS (rows affected = 0)

Thread B gets OptimisticLockingFailureException.
Thread B should RETRY (Resilience4j @Retryable):
  └─ Re-read balance=100
  └─ Check 100 >= 300 ✗ → InsufficientFundsException
  └─ Transfer B fails gracefully

Alice lost only ₹400 (Transfer A). ₹100 protected by Optimistic Locking.
Without @Version: Alice would send ₹700 total with only ₹500 balance → negative balance.
```

### Design Decision: @Transactional on DB, not on HTTP

```
Why can't we roll back the Feign HTTP call from Transaction Service?

Transaction Service calls:
  walletFeignClient.transfer(req)  ← HTTP POST to wallet-service

Wallet Service's @Transactional covers ONLY its own database operations.
Once the wallet-service HTTP 200 response is sent, the money IS moved.

If transaction-service crashes AFTER receiving 200 but BEFORE saving SUCCESS:
  └─ Money is already in Bob's wallet
  └─ Transaction remains PENDING in transaction-service DB
  └─ No Kafka event is published
  └─ Bob has the money, Alice doesn't see a "completed" transaction

This is THE fundamental challenge of distributed transactions:

Solutions (in order of increasing complexity):
1. Idempotent wallet transfer (check if transactionId already processed)
   → Transaction service retries, wallet service says "already done, here's the result"
2. Saga pattern with compensation
   → If transaction-service fails, publish "compensate" event
   → Wallet service has a reverseTransfer endpoint
3. Distributed saga orchestrator
   → A separate orchestrator service tracks state machines
   → Each step has a forward action and a compensating action
```

---

# FLOW 3: Payment Orchestration (The Core)

## 3.1 Happy Path — 6 Steps in Detail

```
POST /transactions/pay
Headers:  Authorization: Bearer <alice-jwt>
Body:     { "requestId": "req-001", "toUpiId": "bob@miniupi", "amount": 200 }
```

### Step 0: JWT Extraction (Controller)

```java
// PayController.java:69-76
private String extractUpiId(String authHeader) {
    String token = authHeader.substring(7); // strip "Bearer "
    SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    return claims.get("upiId", String.class);
    // Returns: "alice@miniupi"  ← NEVER from request body
}
```

**Why extract from JWT, not from request body?**
```
A malicious client could send:
  { "toUpiId": "bob@miniupi", "senderUpiId": "victim@miniupi", "amount": 50000 }

If we trusted the body, the attacker could pay from ANYONE's account.
By extracting sender from JWT, the sender is CRYPTOGRAPHICALLY VERIFIED.
The client can only spend from their own account.
```

### Step 1: Idempotency Check

```java
// TransactionService.java:62-68
var existing = idempotencyService.getExistingResult(requestId);
if (existing.isPresent()) {
    UUID txnId = UUID.fromString(existing.get());
    Transaction txn = transactionRepository.findById(txnId)
        .orElseThrow(() -> new RuntimeException("..."));
    return toResponse(txn, true);  // replayed=true
}
```

**What happens in Redis:**
```
Command: GET idempotency:req-001
Reply:   (nil)  ← key not found, first time

After successful payment:
Command: SET idempotency:req-001 <txnId> EX 86400
Reply:   OK
```

**Why Redis, not Postgres?**
```
Option          | Latency     | Traffic Impact
────────────────┼─────────────┼─────────────────
Postgres query  | ~1-5ms     | Adds load to primary DB
Redis GET       | ~0.1ms     | Offloads DB, scales horizontally

For idempotency, we need:
  ├─ FAST reads (every payment hits this check)
  ├─ TTL expiry (keys auto-delete after 24h)
  ├─ No persistence needed (if Redis dies, worst case = reprocess, idempotent in DB)
  └─ Redis is PERFECT for this
```

### Step 2: Save PENDING Transaction

```java
Transaction txn = Transaction.builder()
    .requestId(requestId)
    .senderUpiId(senderUpiId)
    .receiverUpiId(request.getToUpiId())
    .amount(request.getAmount())
    .status(PENDING)
    .build();
txn = transactionRepository.save(txn);
```

**Why PENDING first?**
```
If the system crashes after wallet transfer but before marking SUCCESS:
  └─ There's a PENDING record in the DB
  └─ Reconciliation process can find all PENDING transactions
  └─ Can either:
     a) Check wallet-service if transfer completed
     b) Mark as FAILED and refund
     c) Retry the transfer

Without PENDING state:
  └─ We'd have "ghost" transfers — money moved but no record
  └─ No way to detect or recover
```

### Step 3: Fraud Check

```java
// FraudEngine.java:43-50
public void validate(String senderUpiId, String receiverUpiId, BigDecimal amount) {
    // Rule 1: Per-transaction cap
    if (amount.compareTo(maxPerTxn) > 0)
        throw new FraudVelocityException("AMOUNT_EXCEEDS_LIMIT", ...);

    // Rule 2: No self-payment
    if (senderUpiId.equals(receiverUpiId))
        throw new FraudVelocityException("SELF_PAYMENT", ...);

    // Rule 3: Daily limit
    Instant startOfDay = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
    BigDecimal dailySent = transactionRepository
        .sumSuccessfulAmountSince(senderUpiId, startOfDay);

    if (dailySent.add(amount).compareTo(dailyLimit) > 0)
        throw new FraudVelocityException("DAILY_LIMIT_EXCEEDED", ...);
}
```

**Database Query for Daily Limit:**
```sql
SELECT COALESCE(SUM(amount), 0)
FROM transactions
WHERE sender_upi_id = 'alice@miniupi'
  AND status = 'SUCCESS'
  AND created_at >= '2024-03-15 00:00:00Z'
```

**Interview Question: "How do you handle the race condition in daily limit check?"**
```
Problem:
  Alice has daily limit of ₹10,000.
  She sends two ₹6,000 payments simultaneously.
  Thread A: reads dailySent = 4000. 4000 + 6000 = 10000 ✓
  Thread B: reads dailySent = 4000. 4000 + 6000 = 10000 ✓
  Both pass! Total = 12000 > 10000.

Answer: There are three approaches:

1. PESSIMISTIC LOCKING — SELECT ... FOR UPDATE on the user's transactions
   └─ Slow, blocks concurrent sends from same user
   └─ Deadlock risk, range-lock on primary key

2. OPTIMISTIC RETRY (current approach)
   └─ Accept the race, check again at commit
   └─ If limit exceeded, mark as FAILED with reason "DAILY_LIMIT_EXCEEDED"
   └─ Transaction eventually consistent, user gets failure message
   └─ This is OK for a learning project but not for production UPI

3. REDIS ATOMIC COUNTER
   └─ `INCR user:daily:alice@miniupi:2024-03-15` by amount
   └─ If result > dailyLimit → reject
   └─ Redis is single-threaded, no race condition
   └─ TTL auto-resets at midnight
   └─ This is what production systems use
```

### Step 4: Wallet Transfer (Feign Call)

```java
// TransactionService.java:82-88
TransferRequest transferReq = new TransferRequest();
transferReq.setTransactionId(txn.getId());  // correlation ID
transferReq.setFromUpiId(senderUpiId);
transferReq.setToUpiId(request.getToUpiId());
transferReq.setAmount(request.getAmount());
walletFeignClient.transfer(transferReq);
```

**What the Feign client sends:**
```
HTTP POST to wallet-service:8082/wallet/internal/transfer
Content-Type: application/json

{
  "transactionId": "uuid-from-transaction-service",
  "fromUpiId": "alice@miniupi",
  "toUpiId": "bob@miniupi",
  "amount": 200.00
}
```

**The Critical Question: "What if this HTTP call succeeds but the next step fails?"**

This is THE most important distributed systems question in this project.

```
Timeline:
  1. walletFeignClient.transfer(req) → HTTP 200 OK  ← Money MOVED
  2. Crash here! (DB deadlock, OOM, kill -9)
  3. txn.setStatus(SUCCESS) → NEVER EXECUTED
  4. idempotencyService.storeResult() → NEVER EXECUTED

State after crash:
  ├─ Wallet: Alice -200, Bob +200  ✓ (money moved)
  ├─ Transaction: PENDING in DB     ✗ (not marked SUCCESS)
  ├─ Redis: no idempotency key      ✗
  └─ Kafka: no event published      ✗

If the client retries (same requestId):
  └─ Idempotency check: key not in Redis → thinks it's NEW
  └─ Fraud check: Alice's daily limit already consumed → might block
  └─ Wallet transfer: sends AGAIN → Alice loses 200 MORE
  └─ DOUBLE CHARGE!

This is the DUAL-WRITE problem in distributed systems.
```

**Production Solutions (in order of how RevPay should evolve):**

```
Solution 1: Idempotent Wallet Transfer (Minimum Viable)
  └─ WalletService checks: "Have I processed this transactionId before?"
  └─ If yes: return cached result instead of processing again
  └─ Makes retries safe — wallet won't debit twice for same transactionId

Solution 2: Saga with Compensation (Recommended for production)
  └─ If Step 5 fails, publish "TRANSACTION_FAILED" event
  └─ Wallet Service listens and calls reverseTransfer()
  └─ Alice gets her money back automatically

Solution 3: Distributed Transaction Coordinator (e.g., Saga Orchestrator)
  └─ Separate service tracks state machine
  └─ Each service sends "command" events
  └─ Orchestrator ensures all-or-nothing across services
  └─ Used by Uber (Cadence), Netflix (Conductor)
```

### Step 5: Mark SUCCESS + Store Idempotency

```java
txn.setStatus(Transaction.TransactionStatus.SUCCESS);
transactionRepository.save(txn);

idempotencyService.storeResult(requestId, txn.getId().toString());
// Redis: SET idempotency:req-001 <txnId> EX 86400
```

**Why TWO writes (DB + Redis) and not one?**
```
DB write is THE source of truth.
  └─ If Redis is down, we can recover from DB (check transactionById)
  └─ DB has the actual transaction record with all fields

Redis write is a PERFORMANCE CACHE.
  └─ Checking Redis is 100x faster than querying DB by requestId
  └─ If Redis is down, fall back to DB: transactionRepository.findByRequestId()
  └─ Redis TTL auto-cleans after 24h

This is the "cache-aside" pattern:
  1. Always check cache first
  2. If miss, check DB
  3. Always write-through to cache
```

### Step 6: Write Outbox Event

```java
private void saveOutboxEvent(Transaction txn, String status, String failureReason) {
    TransactionCompletedEvent event = TransactionCompletedEvent.builder()
        .txnId(txn.getId())
        .status(status)
        .amount(txn.getAmount())
        // ... all fields
        .build();

    OutboxEvent outbox = OutboxEvent.builder()
        .aggregateId(txn.getId().toString())
        .aggregateType("TRANSACTION")
        .eventType("TRANSACTION_" + status)  // "TRANSACTION_SUCCESS" or "TRANSACTION_FAILED"
        .payload(objectMapper.writeValueAsString(event))
        .processed(false)
        .build();

    outboxEventRepository.save(outbox);
    // Same DB transaction as the status update!
}
```

**This write is in the SAME @Transactional scope as Step 5.**
```
If Step 5 (mark SUCCESS) succeeds:
  └─ Outbox event is written (same DB transaction)

If Step 6 (save outbox) fails:
  └─ @Transactional rolls back Step 5 too
  └─ Transaction stays PENDING
  └─ Client gets 500, retries
  └─ Idempotency not stored → safe to retry
```

---

## 3.2 Complete Failure Matrix

| Failure Point | What Breaks | Recovery | Data Loss? |
|--------------|------------|----------|------------|
| JWT expired/invalid | Auth fails | Client re-login | No |
| Redis down (idempotency check) | Can't check duplicates | Fallback: DB query by requestId | No |
| Fraud check fails | Transaction REJECTED | Mark FAILED, notify user | No |
| Wallet Feign call fails | Money not moved | Mark FAILED, outbox FAILED event | No |
| Wallet Feign call SUCCEEDS but next line crashes | Money moved, txn PENDING | **No auto-recovery in current code** | **Possible double charge on retry** |
| DB save SUCCESS fails | Everything rolls back | Client retries | No |
| Outbox write fails | Status saved, no event | Retry on next payment | No (event will be lost) |
| Kafka broker down | OutboxScheduler can't publish | Retries every 2s | No (event stays unprocessed) |
| Notification service down | No SMS sent | Event stays in Kafka, consumed when up | No |

---

## 3.3 Outbox Scheduler Deep Dive

```java
@Scheduled(fixedDelay = 2000)  // Every 2 seconds
@Transactional
public void processOutboxEvents() {
    List<OutboxEvent> unprocessed = outboxEventRepository
        .findByProcessedFalseOrderByCreatedAtAsc();

    for (OutboxEvent event : unprocessed) {
        TransactionCompletedEvent payload = objectMapper
            .readValue(event.getPayload(), TransactionCompletedEvent.class);

        eventPublisher.publishSync(payload);
        // Blocks until Kafka ACKs (or throws)

        event.setProcessed(true);
        event.setProcessedAt(Instant.now());
        outboxEventRepository.save(event);
    }
}
```

**Why `fixedDelay` and not `fixedRate`?**
```
fixedRate = 2000ms: runs every 2s regardless of whether previous run finished
fixedDelay = 2000ms: runs 2s AFTER previous run completes

If a batch takes 10s (500 events × 20ms each):
  └─ fixedRate: second invocation starts at 2s (OVERLAP)
  └─ fixedDelay: second invocation starts at 12s

fixedDelay is safer:
  ├─ No overlap → no concurrent processing of same events
  ├─ Predictable load — one poll at a time
  └─ Backpressure — if Kafka is slow, we naturally poll less frequently
```

**What if the scheduler crashes after publishing but before marking processed?**
```
Timeline:
  1. publishSync() → Kafka ACKs ✓  ← event is in Kafka now
  2. Crash! (power outage)
  3. setProcessed(true) + save() → NEVER EXECUTED

On restart:
  └─ Next poll: SELECT * FROM outbox_events WHERE processed = false
  └─ The same event is picked up AGAIN
  └─ Published to Kafka AGAIN
  └─ DUPLICATE MESSAGE in Kafka

Notification Service must handle this:
  └─ Check: "Have I already sent this txnId?"
  └─ If yes → skip (idempotent consumer)

The outbox pattern guarantees AT-LEAST-ONCE delivery.
The consumer must handle duplicates.
```

---

# FLOW 4: Kafka Eventing

## Topic Structure

| Topic | Partitions | Retention | Key | Value |
|-------|-----------|-----------|-----|-------|
| `user.created` | 3 | 7 days | userId (UUID) | UserCreatedEvent JSON |
| `txn.completed` | 3 | 7 days | txnId (UUID) | TransactionCompletedEvent JSON |
| `txn.failed` | 3 | 7 days | txnId (UUID) | TransactionCompletedEvent JSON |

## Consumer Groups

```
wallet-service-group:
  ├─ user.created → UserCreatedListener (create wallet)

notification-service-group:
  ├─ user.created → UserCreatedListener (send welcome SMS)
  ├─ txn.completed → TransactionEventListener (send debit/credit alerts)
  └─ txn.failed → TransactionEventListener (send failure alert)
```

**Why separate consumer groups?**
```
wallet-service-group:
  ├─ OFFSET managed by wallet service
  └─ If notification service is down, wallet service still consumes

notification-service-group:
  ├─ OFFSET managed by notification service
  └─ If wallet service is down, notifications still go through

Both groups read the SAME topic independently.
Each group gets ALL messages — they process different concerns.
```

---

# FLOW 5: API Gateway

## JWT Auth Filter Flow

```
Request arrives at Gateway
  │
  ├─ Path = /auth/** → skip JWT check, route directly
  ├─ Path = /swagger-ui/** → skip JWT check
  │
  └─ Path = anything else → JWT validation:
       │
       ├─ Extract "Authorization: Bearer <token>"
       │   └─ Missing? → 401 UNAUTHORIZED
       │
       ├─ Parse JWT:
       │   Jwts.parser()
       │     .verifyWith(Keys.hmacShaKeyFor(secret.getBytes()))
       │     .build()
       │     .parseSignedClaims(token)
       │       .getPayload()
       │   └─ Invalid? (expired, bad signature) → 401 UNAUTHORIZED
       │
       └─ Valid → store claims in exchange attributes:
           exchange.getAttributes().put("upiId", claims.get("upiId"))
           └─ Forward to downstream service
```

## Rate Limiting

```
RequestRateLimiter filter:
  ├─ KeyResolver: by Client IP (GatewayConfig)
  ├─ Redis token bucket:
  │    └─ Bucket capacity: burstCapacity (e.g., 40)
  │    └─ Replenish rate: replenishRate per second (e.g., 20)
  │
  ├─ Available tokens > 0? → consume 1, forward request
  └─ Available tokens = 0? → 429 Too Many Requests

Per-route limits:
  ├─ /users/** → 20/s, burst 40
  ├─ /wallet/** → 20/s, burst 40
  └─ /transactions/** → 10/s, burst 20 (payments are expensive)
```

---

# SYSTEM DESIGN INTERVIEW QUESTIONS

## Q1: How does RevPay prevent double charges?

```
Three layers of defense:

1. IDEMPOTENCY KEY (Redis)
   └─ Client generates UUID, sends with every payment request
   └─ Before processing: Redis GET idempotency:{requestId}
   └─ If found: return cached response IMMEDIATELY, no processing

2. UNIQUE CONSTRAINT (PostgreSQL)
   └─ transactions.request_id has a UNIQUE constraint
   └─ Even if Redis is down and two threads try to insert:
        INSERT INTO transactions (request_id, ...) VALUES ('req-001', ...)
        └─ Second insert fails → constraint violation
        └─ Catch → SELECT by request_id → return existing result

3. @Version (Optimistic Locking on Wallet)
   └─ Even if a transfer is retried, wallet balance only changes once
   └─ Second attempt gets OptimisticLockException → retry → reads updated balance
```

## Q2: What happens if Redis goes down?

```
Impact:
  ├─ Idempotency: can't check Redis → fallback to DB query (findByRequestId)
  ├─ Rate limiting: token buckets empty → all requests pass through
  └─ JWT auth: unaffected (uses local secret key)

Recovery:
  ├─ Redis is stateless for idempotency — keys have TTL, no data loss
  ├─ Redis comes back → new keys are created fresh
  └─ Rate limiting restarts with full buckets

No permanent data loss in any scenario.
```

## Q3: How would you scale this to 1 million transactions per day?

```
Day has 86,400 seconds → 1M/86400 ≈ 12 transactions/second
Not a huge number — the current design can handle this.

But to scale further:

1. HORIZONTAL SCALING
   ├─ Each service is stateless → run multiple instances behind ALB
   ├─ API Gateway is the bottleneck? → more Gateway instances
   ├─ Transaction Service is the bottleneck? → more instances
   ├─ Database is the bottleneck? → read replicas, sharding
   └─ Redis is the bottleneck? → Redis Cluster

2. DATABASE SHARDING
   ├─ Shard transactions by sender_upi_id hash
   ├─ Each shard on separate PostgreSQL instance
   └─ OR use managed DB (AWS Aurora, CockroachDB)

3. KAFKA PARTITIONING
   ├─ More partitions = more parallel consumers
   ├─ Key by txnId ensures ordering per transaction
   └─ Each partition can be consumed independently

4. CACHING
   ├─ Cache fraud daily limits in Redis (avoid sum query on every payment)
   ├─ Cache user profiles in Redis (avoid hitting user DB for every lookup)
   └─ Use Redis hash with TTL for bounded memory
```

## Q4: Walk me through the payment flow from the client's perspective.

```
Client (Mobile App):

1. User enters: Bob's UPI ID ("bob@miniupi"), amount (₹200), PIN (1234)

2. App generates: requestId = UUID.randomUUID().toString()
   └─ This is the idempotency key

3. App sends:
   POST /transactions/pay
   Authorization: Bearer <jwt-from-login>
   {
     "requestId": "550e8400-...",
     "toUpiId": "bob@miniupi",
     "amount": 200
   }

4. App receives:
   Case A: 201 CREATED → "Payment successful!"
   Case B: 200 OK → "Already processed!" (safe replay)
   Case C: 4xx/5xx → Show error, offer retry with SAME requestId

5. If retry needed → app sends EXACTLY the same request
   └─ requestId is the same → idempotent → no double charge

6. SMS received:
   "₹200 debited from alice@miniupi. Txn Ref: abc-123"
```

## Q5: How is this different from what PhonePe/Google Pay does?

```
Production UPI (NPCI) vs RevPay:

Feature              | NPCI UPI              | RevPay
─────────────────────┼───────────────────────┼────────────────────
Idempotency          | PSP manages txn ID    | Client-provided requestId
Fraud detection      | ML models, real-time  | Static rules (limit, cap, self-pay)
Database             | Oracle/Teradata       | PostgreSQL
Messaging            | ISO 8583 over TCP     | JSON over HTTP/Kafka
Payment guarantee    | National payment sys  | No inter-bank settlement
Authorization        | UPI PIN at NPCI       | JWT at Gateway
Outbox pattern       | Internal queue        | PostgreSQL outbox

The patterns are the same — just different scale and compliance requirements.
NPCI handles 40 crore+ transactions per day.
RevPay demonstrates the same patterns at a smaller scale.
```

## Q6: How would you add a new fraud rule?

```
Example: "Block transactions above ₹10,000 between 10 PM and 6 AM"

Add to FraudEngine.java:

    @Value("${fraud.night-limit:10000}")
    private BigDecimal nightLimit;

    @Value("${fraud.night-start:22}")
    private int nightStart;

    @Value("${fraud.night-end:6}")
    private int nightEnd;

    // In validate() method:
    int hour = LocalTime.now().getHour();
    boolean isNight = hour >= nightStart || hour < nightEnd;

    if (isNight && amount.compareTo(nightLimit) > 0) {
        throw new FraudVelocityException("NIGHT_LIMIT_EXCEEDED",
            "Transactions above ₹" + nightLimit + " are blocked between " +
            nightStart + ":00 and " + nightEnd + ":00");
    }

This demonstrates:
  ├─ Configuration-driven rules (no code change for threshold)
  ├─ Single responsibility (FraudEngine owns all rules)
  └─ Easy to test (unit test with mocked time)
```

---

# GLOSSARY OF PATTERNS

| Pattern | Where Used | Why |
|---------|-----------|-----|
| **Transactional Outbox** | User Service, Transaction Service | Atomic DB + event writes without dual-write |
| **Idempotency Key** | Transaction Service | Safe retries, no double charge |
| **Optimistic Locking** | Wallet Service | Prevent concurrent balance corruption |
| **CQRS** | Ledger Read vs Wallet Write | Separate read model (ledger) from write model (balance) |
| **Event-Driven Architecture** | Kafka across all services | Loose coupling, async processing |
| **Strangler Fig** | Nginx → ALB migration | Incrementally replace monolithic component |
| **Database per Service** | All services | Independent scaling, schema isolation |
| **API Gateway** | Spring Cloud Gateway | Single entry point, cross-cutting concerns |
| **Saga (partial)** | Transaction → Wallet | Distributed transaction without 2PC |
| **Retry with Backoff** | Resilience4j on Feign calls | Handle transient wallet-service failures |

---

# CODE NAVIGATION REFERENCE

| Question | File to Read |
|----------|-------------|
| "How does the payment flow work?" | `TransactionService.java` (the `pay()` method) |
| "How is idempotency implemented?" | `IdempotencyService.java` |
| "What fraud rules exist?" | `FraudEngine.java` |
| "How does the outbox work?" | `OutboxScheduler.java` + `OutboxEvent.java` |
| "How are wallets updated?" | `WalletService.java` (the `transfer()` method) |
| "How is JWT validated at the edge?" | `JwtAuthFilter.java` (api-gateway) |
| "How are users registered?" | `UserService.java` (the `register()` method) |
| "How is the database initialized?" | `infra/init-db.sql` |
| "What Kafka topics exist?" | `KafkaTopics.java` (common) |
| "How do services discover each other?" | `application.yml` (each service, `wallet.service.url`) |
