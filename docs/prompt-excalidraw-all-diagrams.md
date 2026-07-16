# Excalidraw Diagram Prompts — RevPay Interview Deep Dive

This file contains prompts for every diagram referenced in `INTERVIEW_DEEP_DIVE.md`.  
Copy each prompt into any AI tool (ChatGPT, Claude, etc.) to generate the Excalidraw JSON for that diagram.

---

## Global Style Instructions (apply to all diagrams)

- White/light background, blue/green accent colors
- Rounded rectangle containers for services (stroke width 2)
- Solid arrows for synchronous calls, dashed arrows for async/events
- Label all arrows with relevant method/action text
- Use `═══` box styling for databases, `◉` for Kafka topics
- Legend at bottom-right corner explaining arrow styles

---

# FLOW 1: User Registration

## 1.2 Edge Cases & Failure Scenarios

Generate an Excalidraw diagram JSON for **1.2 Edge Cases & Failure Scenarios** of the RevPay User Registration flow.

**Layout** (left-to-right):
- Left column: "User Service" box
- Right column: "PostgreSQL" box

**Scenarios to show as separate swimlanes/rows:**

### Row 1: Scenario A — Duplicate Phone Number
```
Client → User Service: POST /api/auth/register {phone: "9876543210"}
User Service → PostgreSQL: SELECT * FROM users WHERE phone = '9876543210'
PostgreSQL → User Service: returns existing User (row found)
User Service → Client: 409 CONFLICT {error: "PHONE_ALREADY_EXISTS"}
```
- Arrow labels: `POST`, `SELECT`, `returns User`, `409`
- Show the flow with solid arrows, highlight the 409 response in red

### Row 2: Scenario D — User Save Succeeds, Outbox Write Fails
```
timeline (vertical numbers 1→2):
  1. User Service → PostgreSQL: INSERT INTO users → OK ✓
  2. User Service → PostgreSQL: INSERT INTO outbox_events → FAIL ✗ (constraint violation)
```
- Show `@Transactional` rollback annotation (dashed red arrow back from step 2 to step 1)
- Label: "ROLLBACK — user NOT created"
- Show "Client ← 500 ERROR" arrow

### Row 3: Scenario E — Kafka is Down After Registration
```
timeline:
  User Service → PostgreSQL: INSERT user + outbox_event (processed=false) ✓
  User Service - - → Kafka: publishSync() → Connection refused ✗
  OutboxScheduler (loop icon): retry every 2s
  Later: Kafka comes back up → Kafka - - → Wallet Service: createWallet()
  Kafka - - → Notification Service: sendWelcome()
```
- Use dashed arrows for async Kafka events
- Show "retry loop" with a circular arrow on OutboxScheduler
- Green checkmark on final successful delivery

---

## Scenario A: Duplicate Phone Number

Generate an Excalidraw diagram JSON for **Scenario A: Duplicate Phone Number** in RevPay.

**Elements**: `DB`, `→`

**Layout** (top-to-bottom):
- Box: "Client" → solid arrow labeled `POST /api/auth/register {phone}` → "User Service"
- "User Service" → solid arrow labeled `UserRepository.findByPhone()` → "PostgreSQL DB"
- "PostgreSQL DB" → solid arrow labeled `returns existing User` → "User Service"
- "User Service" → solid arrow (red stroke #e03131) labeled `HTTP 409 CONFLICT "PHONE_ALREADY_EXISTS"` → "Client"

Below the main flow, add a callout box:
```
Error Response:
{
  "success": false,
  "error": {
    "code": "PHONE_ALREADY_EXISTS",
    "message": "Phone already registered"
  }
}
```

---

## Scenario B: Duplicate UPI ID

Generate an Excalidraw diagram JSON for **Scenario B: Duplicate UPI ID** in RevPay.

**Elements**: `DB`, `→`

**Layout** (top-to-bottom, two parallel solutions):

### Solution 1 (current) — left column
- "UpiIdGenerator" → generates `"alice@miniupi"`
- "UpiIdGenerator" → `SELECT * FROM users WHERE upi_id = 'alice@miniupi'` → "PostgreSQL"
- "PostgreSQL" → `returns row (taken)` → "UpiIdGenerator"
- "UpiIdGenerator" → `appends counter` → generates `"alice2@miniupi"`
- Solid arrow labeled `INSERT "alice2@miniupi"` → "PostgreSQL (UNIQUE constraint as last resort)"

### Solution 2 (better) — right column
- "UpiIdGenerator" → generates `"alice@miniupi"`
- "UpiIdGenerator" → `appends random 4-char suffix` → generates `"alice@miniupi_x7k2"`
- Solid arrow labeled `INSERT with random suffix` → "PostgreSQL"

Add a note box at bottom: "UNIQUE constraint in DB is the final safety net"

---

## Scenario D: User Save Succeeds, Outbox Write Fails

Generate an Excalidraw diagram JSON for **Scenario D: User Save Succeeds, Outbox Write Fails** in RevPay.

**Elements**: `DB`, `→`

**Layout** (timeline, top-to-bottom):
- "Step 1" label: User Service → PostgreSQL: `INSERT INTO users` → `OK ✓` (green check)
- "Step 2" label: User Service → PostgreSQL: `INSERT INTO outbox_events` → `FAIL ✗` (red X, constraint violation)
- Dashed red arrow labeled `@Transactional ROLLBACK` going from Step 2 back over Step 1
- Callout box:
```
Result:
  - Step 1 ROLLS BACK → user NOT created
  - Client receives 500 error
  - Client retries → idempotent (phone unique → 409, not duplicate)
  - "Either both succeed, or neither. Never one without the other."
```

---

## Scenario E: Kafka is Down After Registration

Generate an Excalidraw diagram JSON for **Scenario E: Kafka is Down After Registration** in RevPay.

**Elements**: `Kafka`, `SMS`, `→`

**Layout** (chronological timeline, top-to-bottom):

### Phase 1: Registration succeeds
- "User Service" → solid arrow `INSERT user + outbox_event (processed=false)` → "PostgreSQL"
- Green checkmark: "User created successfully"

### Phase 2: Outbox publisher fails
- "User Service (OutboxScheduler)" → dashed red arrow `publishSync() → Connection refused` -/-> "Kafka"
- "OutboxScheduler" with circular retry arrow: `Retry every 2s`

### Phase 3: Kafka recovers
- "Kafka" (now green/online)
- "OutboxScheduler" → dashed green arrow `publishSync() → ACK` → "Kafka"
- "Kafka" → dashed green arrow `user.created event` → "Wallet Service" → `createWallet(upiId)`
- "Kafka" → dashed green arrow `user.created event` → "Notification Service" → `sendWelcome()`
- "Notification Service" → dashed arrow labeled `[SMS] Welcome to RevPay!` → "User's Phone"

Bottom callout box:
```
"The outbox pattern gives at-least-once delivery even when Kafka is down.
 The consumer must be idempotent (wallet creation already is —
 it checks existsByUserId first)."
```

---

## 1.3 Design Decisions

Generate an Excalidraw diagram JSON for **1.3 Design Decisions** of RevPay.

**Elements**: `Kafka`, `DB`, `←`, `→`

**Layout** (two columns comparing approaches):

### Left Column: Anti-Pattern — Dual Write
- "User Service" → solid arrow `userRepository.save(user)` → "PostgreSQL ✓"
- "User Service" → dashed red arrow `kafkaTemplate.send("user.created")` -/-> "Kafka ✗ (fails)"
- Red text: "❌ User saved but NO event → wallet never created"
- Big red "DUAL-WRITE PROBLEM" label

### Right Column: Correct Pattern — Transactional Outbox
- "User Service" → solid arrow `userRepository.save(user)` → "PostgreSQL"
- "User Service" → solid arrow `outboxEventRepository.save(outbox)` → "PostgreSQL (same TX)"
- Bracket annotation: "✅ SAME database transaction — succeed or fail together"
- "OutboxScheduler" (clock icon) polls → dashed green arrow `publishSync()` → "Kafka"
- "Kafka" → dashed green arrow → Consumers (Wallet, Notification)
- Green check: "✅ At-least-once delivery guaranteed"

---

## Why Separate DB Per Service?

Generate an Excalidraw diagram JSON for **Why separate DB per service?** in RevPay.

**Elements**: `DB`, `→`

**Layout** (three columns, side by side):

### Column 1 (left)
- Box: "User Service" → arrow → "DB: upi_users"
- Sub-list: users, outbox_events

### Column 2 (center)
- Box: "Wallet Service" → arrow → "DB: upi_wallets"
- Sub-list: wallets (@Version), ledger_entries

### Column 3 (right)
- Box: "Transaction Service" → arrow → "DB: upi_transactions"
- Sub-list: transactions, outbox_events

### Annotations below:
```
Benefits (green):
  ✓ Loose coupling — schema changes don't cascade
  ✓ Independent scaling — wallet DB can be 10x larger
  ✓ Each service owns its data — no merge conflicts
  ✓ Isolation — txn-svc outage doesn't affect user-svc

Drawback (orange):
  ✗ Cross-service queries need API calls (no JOIN across DBs)
    Example: get transactions for user → call user-svc for upiId,
    then call txn-svc → two network hops, eventual consistency
```

---

# FLOW 2: Wallet Operations

## Edge Case: Concurrent Add-Money Calls

Generate an Excalidraw diagram JSON for **Edge Case: Concurrent Add-Money Calls** in RevPay.

**Elements**: `→`

**Layout** (two parallel threads, side by side):

### Thread A (left)
- `read balance=0 (version=1)` → arrow down
- `set balance=5000` → arrow down
- `UPDATE wallets SET balance=5000, version=2 WHERE id=uuid AND version=1`
- Green check: `✅ SUCCESS (rows affected = 1)`

### Thread B (right)
- `read balance=0 (version=1)` → arrow down
- `set balance=3000` → arrow down
- `UPDATE wallets SET balance=3000, version=2 WHERE id=uuid AND version=1`
- Red X: `❌ FAILS (rows affected = 0)` because Thread A already updated version to 2
- Orange dashed arrow: `@Retryable → retry`
- `re-read balance=5000 (version=2)` → `set balance=8000` → `UPDATE SUCCESS`

### Bottom comparison:
```
Without @Version (red):
  Thread B OVERWRITES Thread A → ₹5000 lost → negative balance catastrophe

With @Version (green):
  OptimisticLockException → retry → correct state → money safe
```

---

## Failure: Insufficient Funds

Generate an Excalidraw diagram JSON for **Failure: Insufficient Funds** in RevPay.

**Elements**: `SMS`, `→`

**Layout** (top-to-bottom, left-to-right):

### Flow:
- "Client" → `POST /transactions/pay {amount: 200}` → "Transaction Service"
- "Transaction Service" → Feign arrow `POST /wallet/internal/transfer {amount: 200}` → "Wallet Service"
- "Wallet Service" → `check: sender balance=100, amount=200`
- "Wallet Service" → red arrow `throws InsufficientFundsException` → "Transaction Service"
- "Transaction Service" → `@Transactional ROLLBACK`
- "Transaction Service" → solid arrow `UPDATE SET status=FAILED, reason=insufficient funds` → "PostgreSQL"
- "Transaction Service" → dashed arrow `INSERT outbox_event (TRANSACTION_FAILED)` → "PostgreSQL"

### Async continuation:
- "OutboxScheduler" → dashed green arrow `publish txn.failed event` → "Kafka"
- "Kafka" → dashed green arrow → "Notification Service"
- "Notification Service" → dashed green arrow labeled `[SMS] Payment of ₹200 failed. Reason: insufficient funds` → "User's Phone"

### Callout box:
```
Alice balance = ₹100, tries to send ₹200.
sender.getBalance().compareTo(amount) < 0
→ InsufficientFundsException
→ @Transactional rolls back everything
→ No partial state, no money lost
```

---

# FLOW 3: Payment Orchestration (The Core)

## System Flow — Payment Orchestration (6 Steps)

Generate an Excalidraw diagram JSON for the **complete payment orchestration flow** in RevPay.

**Elements**: `Feign`, `Kafka`, `POST`, `HTTP`, `DB`, `←`, `→`

**Layout** (top-to-bottom, 7 component columns):

### Components (header row):
| Client | API Gateway | Transaction Service | Redis | Wallet Service | PostgreSQL | Kafka |

### Steps shown sequentially:

**Step 0: JWT Extraction**
- Client → Gateway: `POST /transactions/pay {requestId, toUpiId, amount}` + `Authorization: Bearer <jwt>`
- Gateway → Transaction Service: forward request + `extractUpiId() from JWT claims`
- Callout: "Sender extracted from JWT, NEVER from request body — prevents account takeover"

**Step 1: Idempotency Check**
- Transaction Service → Redis: `GET idempotency:<requestId>`
- Redis → Transaction Service: `(nil)` — first time
- Callout: "Cache miss → proceed with payment"

**Step 2: Save PENDING**
- Transaction Service → PostgreSQL: `INSERT transactions (status=PENDING)`
- PostgreSQL → Transaction Service: `OK`

**Step 3: Fraud Check**
- Transaction Service → PostgreSQL: `SELECT SUM(amount) FROM transactions WHERE sender=alice AND status=SUCCESS AND today`
- PostgreSQL → Transaction Service: `dailySent=4000`
- Transaction Service internal check: `4000 + 200 <= 10000 ✓` → pass
- Callout: "Rules: amt≤₹50K, no self-pay, daily≤₹10K"

**Step 4: Wallet Transfer**
- Transaction Service → Wallet Service (Feign): `POST /wallet/internal/transfer {transactionId, fromUpiId, toUpiId, amount}`
- Wallet Service internal: atomic debit sender + credit receiver with @Version
- Wallet Service → Transaction Service: `200 OK`
- Callout: "This is the critical step — money moves HERE"

**Step 5: Mark SUCCESS + Store Idempotency**
- Transaction Service → PostgreSQL: `UPDATE transactions SET status=SUCCESS`
- Transaction Service → Redis: `SET idempotency:<requestId>=<txnId> EX 86400`

**Step 6: Write Outbox Event**
- Transaction Service → PostgreSQL: `INSERT outbox_events (processed=false)`
- (Same DB transaction as Step 5 — succeed or rollback together)

**Async: OutboxScheduler → Kafka → Notification**
- OutboxScheduler → Kafka: `publishSync("txn.completed")`
- Kafka → Notification Service: `onTransactionEvent()`
- Notification Service → Client: `[SMS] ₹200 debited from alice@miniupi`

---

## System Flow — Idempotency Check (Step 1)

Generate an Excalidraw diagram JSON for **Step 1: Idempotency Check** in RevPay.

**Elements**: `Redis`, `GET`, `DB`

**Layout** (two lanes side by side):

### Lane 1: First Time (new request)
- "Transaction Service" → arrow `GET idempotency:req-001` → "Redis"
- "Redis" → arrow `(nil) — cache miss` → "Transaction Service"
- "Transaction Service" → arrow `proceed to process payment` → (down arrow)

### Lane 2: Replayed Request (same requestId)
- "Transaction Service" → arrow `GET idempotency:req-001` → "Redis"
- "Redis" → arrow `<cached txnId>` → "Transaction Service"
- "Transaction Service" → arrow `findById(txnId)` → "PostgreSQL"
- "PostgreSQL" → arrow `existing SUCCESS transaction` → "Transaction Service"
- "Transaction Service" → arrow `PayResponse {replayed: true}` → "Client"
- Callout: "Safe replay — no money moved, no fraud check, no Feign call"

### Bottom annotation:
```
Why Redis:
  - GET: ~0.1ms (vs Postgres query: ~1-5ms)
  - TTL auto-expiry after 24h
  - No persistence needed — if Redis dies, fallback to DB query
  - Redis is PERFECT for this
```

---

## System Flow — Save PENDING (Step 2)

Generate an Excalidraw diagram JSON for **Step 2: Save PENDING Transaction** in RevPay.

**Elements**: `DB`

**Layout**:
- "Transaction Service" → arrow `Transaction txn = Transaction.builder().status(PENDING).build()` → "PostgreSQL"
- "PostgreSQL" → arrow `INSERT INTO transactions (request_id, sender_upi_id, receiver_upi_id, amount, status='PENDING')` → success

### Callout box (right side):
```
Why PENDING first?

If crash after wallet transfer but before marking SUCCESS:
  → PENDING record exists in DB
  → Reconciliation can find it
  → Options:
    a) Check wallet if transfer completed
    b) Mark FAILED and refund
    c) Retry

Without PENDING state:
  → "Ghost" transfers — money moved but no record
  → No way to detect or recover
```

---

## System Flow — Mark SUCCESS + Store Idempotency (Step 5)

Generate an Excalidraw diagram JSON for **Step 5: Mark SUCCESS + Store Idempotency** in RevPay.

**Elements**: `Redis`, `→`

**Layout** (parallel arrows):
- "Transaction Service" — dual simultaneous arrows:
  - Left arrow: `txn.setStatus(SUCCESS)` → `PostgreSQL` → `UPDATE transactions WHERE id=uuid`
  - Right arrow: `idempotencyService.storeResult(requestId, txnId)` → `Redis` → `SET idempotency:req-001 <txnId> EX 86400`

### Annotation box:
```
DB = source of truth
  - If Redis is down → recover from DB (findByRequestId)

Redis = performance cache
  - 100x faster than DB query
  - TTL auto-cleans after 24h

This is the "cache-aside" pattern:
  1. Always check cache first
  2. If miss, check DB
  3. Write-through to cache
```

---

## System Flow — Write Outbox Event (Step 6)

Generate an Excalidraw diagram JSON for **Step 6: Write Outbox Event** in RevPay.

**Elements**: `DB`

**Layout**:
- "Transaction Service" (inside @Transactional boundary) → arrow `saveOutboxEvent()`
- Arrow branched:
  - Branch 1: `INSERT outbox_events (event_type="TRANSACTION_SUCCESS", processed=false)` → "PostgreSQL"
  - Branch 2: `INSERT outbox_events (event_type="TRANSACTION_FAILED", processed=false)` → "PostgreSQL"

### Transaction boundary box:
```
Both Step 5 and Step 6 are in the SAME @Transactional scope:

  Step 5 succeeds + Step 6 succeeds → ✅ Both committed
  Step 5 succeeds + Step 6 fails    → 🔄 Step 5 ROLLS BACK
  (Transaction stays PENDING, client retries → safe)
```

---

## System Flow — Complete Failure Matrix / Critical Question

Generate an Excalidraw diagram JSON for **The Critical Distributed Systems Question** in RevPay.

**Elements**: `Feign`, `Redis`, `Kafka`, `HTTP`, `DB`, `←`, `→`

**Layout** (timeline, numbered steps 1-4):

### The Crash Scenario:
```
Step 1: Transaction Service —Feign→ Wallet Service: POST /wallet/internal/transfer
Step 2: Wallet Service → Transaction Service: HTTP 200 OK  ← Money MOVED
Step 3: 🔥 CRASH HERE (DB deadlock, OOM, kill -9)
Step 4: txn.setStatus(SUCCESS) → NEVER EXECUTED (grayed out)
Step 4b: Redis SET idempotency key → NEVER EXECUTED (grayed out)
```

### State After Crash (red warning box):
```
State:
  ✓ Wallet: Alice -₹200, Bob +₹200 (money moved)
  ✗ Transaction: PENDING in DB (not SUCCESS)
  ✗ Redis: no idempotency key
  ✗ Kafka: no event published

If client retries (same requestId):
  1. Idempotency check: key NOT in Redis → thinks NEW request
  2. Fraud check: daily limit already consumed → might block
  3. Wallet Feign call: sends AGAIN → Alice loses ₹200 MORE
  → DOUBLE CHARGE!
```

### Solutions (below, three columns):
```
Solution 1 (MVP): Idempotent Wallet Transfer
  WalletService checks: "Have I processed this transactionId?"
  If yes → return cached result → safe retry

Solution 2 (Recommended): Saga with Compensation
  Publish TRANSACTION_FAILED event → Wallet reverseTransfer()
  Alice gets money back automatically

Solution 3 (Enterprise): Distributed Transaction Coordinator
  Separate service tracks state machine
  Used by Uber (Cadence), Netflix (Conductor)
```

---

## System Flow — Why PENDING First? (decision box)

Generate an Excalidraw diagram JSON for **Why PENDING first?** in RevPay.

**Elements**: `system components`

**Layout**: A decision-tree style diagram:
- Box: "Save transaction as PENDING first"
- Two branches after wallet Feign call:
  - ✅ Success: `→ UPDATE status=SUCCESS` → `happy path`
  - ❌ Crash after Feign success: → `PENDING record exists` → `Reconciliation options:`
    - `a) Check wallet-service if transfer completed`
    - `b) Mark FAILED and refund`
    - `c) Retry the transfer`

### Comparison box:
```
With PENDING:
  Crash → PENDING record → reconciliation can recover

Without PENDING:
  Crash → no record → "ghost" transfer → money missing
  → No way to detect or recover
```

---

## Why `fixedDelay` and not `fixedRate`?

Generate an Excalidraw diagram JSON for **Why `fixedDelay` and not `fixedRate`?** in RevPay.

**Elements**: `Kafka`, `→`

**Layout** (two timelines side by side):

### Left: `fixedRate(2000ms)` — PROBLEM
```
Timeline:
  [0s]     Run 1 starts (batch: 500 events, takes 10s)
  [2s]     🔥 Run 2 starts (OVERLAP with Run 1!)
  [4s]     🔥 Run 3 starts (more overlap)
  [10s]    Run 1 finishes
  ...
```
Red warning: "Concurrent processing of same events! Race condition!"

### Right: `fixedDelay(2000ms)` — CORRECT
```
Timeline:
  [0s]     Run 1 starts (batch: 500 events, takes 10s)
  [10s]    Run 1 finishes
  [12s]    Run 2 starts
  [22s]    Run 2 finishes
  [24s]    Run 3 starts
```
Green check: "No overlap → predictable load → natural backpressure"

### Bottom callout:
```
Benefits:
  ✓ No concurrent processing of same events
  ✓ Predictable load — one poll at a time
  ✓ Backpressure — if Kafka is slow, we naturally poll less
```

---

## System Flow — Scheduler Crashes After Publish

Generate an Excalidraw diagram JSON for **Scheduler crash after publish but before marking processed** in RevPay.

**Elements**: `Kafka`, `←`, `→`

**Layout** (timeline, numbered steps):

### Phase 1: Crash
```
Step 1: OutboxScheduler → Kafka → publishSync() → Kafka ACKs ✓
         (event IS in Kafka now)
Step 2: 🔥 CRASH (power outage)
Step 3: setProcessed(true) + save() → NEVER EXECUTED (grayed out)
```

### Phase 2: Recovery
```
On restart:
  OutboxScheduler → PostgreSQL: SELECT * FROM outbox_events WHERE processed=false
  PostgreSQL → OutboxScheduler: returns SAME event (still unprocessed!)
  OutboxScheduler → Kafka: publishes AGAIN
  → DUPLICATE MESSAGE in Kafka
```

### Phase 3: Consumer handling
```
Notification Service:
  Check: "Have I already sent this txnId?"
  If yes → skip → idempotent consumer ✓
```

### Bottom annotation:
```
The outbox pattern guarantees AT-LEAST-ONCE delivery.
The consumer MUST handle duplicates.
```

---

# FLOW 4: Kafka Eventing

## Consumer Groups

Generate an Excalidraw diagram JSON for **FLOW 4: Kafka Eventing — Consumer Groups** in RevPay.

**Elements**: `SMS`, `→`

**Layout** (star topology):

### Center: "Kafka" with 3 topic boxes:
- Topic 1: `user.created` (3 partitions, 7 day retention)
- Topic 2: `txn.completed` (3 partitions, 7 day retention)
- Topic 3: `txn.failed` (3 partitions, 7 day retention)

### Left: "Wallet Service" box
- Sub-box: `wallet-service-group`
- Arrow from `user.created` topic → `UserCreatedListener` → `createWallet()`

### Right: "Notification Service" box
- Sub-box: `notification-service-group`
- Three arrows:
  - `user.created` → `UserCreatedListener` → `sendWelcome()`
  - `txn.completed` → `TransactionEventListener` → `sendDebitAlert() + sendCreditAlert()`
  - `txn.failed` → `TransactionEventListener` → `sendFailureAlert()`
- Dashed arrows from Notification Service → "User's Phone": `[SMS]`

### Legend:
```
Solid lines from Kafka to each service = consumer group subscription
Each group reads the SAME topic independently
Each group gets ALL messages — they process different concerns
```

---

## Why Separate Consumer Groups?

Generate an Excalidraw diagram JSON for **Why separate consumer groups?** in RevPay.

**Elements**: `system components`

**Layout** (two panels showing independence):

### Panel 1: Notification Service is DOWN
```
Kafka (user.created topic)
  │
  ├──→ [wallet-service-group] → Wallet Service ✅
  │     → createWallet() → SUCCESS
  │
  └──→ [notification-service-group] → Notification Service ❌ (down)
        → OFFSET NOT committed → messages stay in Kafka
        → When notification-svc comes back → consumes from last committed offset
```
Green check: "Wallet service unaffected, notifications catch up later"

### Panel 2: Wallet Service is DOWN
```
Kafka (user.created topic)
  │
  ├──→ [wallet-service-group] → Wallet Service ❌ (down)
  │     → OFFSET NOT committed → messages stay in Kafka
  │     → When wallet-svc comes back → consumes from last committed offset
  │
  └──→ [notification-service-group] → Notification Service ✅
        → sendWelcome() → SUCCESS
```
Green check: "Notifications go through, wallet catches up later"

### Bottom summary:
```
Both groups read the SAME topic independently.
Each group manages its own offset.
Downstream failures are isolated.
```

---

# FLOW 5: API Gateway

## JWT Auth Filter Flow

Generate an Excalidraw diagram JSON for **JWT Auth Filter Flow** in RevPay.

**Elements**: `JWT`, `→`

**Layout** (decision tree, top-to-bottom):

```
Request arrives at API Gateway
  │
  ├─ Is path = /auth/** or /swagger-ui/**?
  │   ├─ YES → 🟢 skip JWT check → route to downstream service
  │   └─ NO → continue to JWT validation
  │            │
  │            ├─ Does Authorization header exist?
  │            │   ├─ NO → 🔴 401 UNAUTHORIZED (missing token)
  │            │   └─ YES → extract "Bearer <token>"
  │            │            │
  │            │            ├─ Parse JWT: Jwts.parser().verifyWith(secretKey)
  │            │            │   ├─ Invalid (expired/bad signature) → 🔴 401
  │            │            │   └─ Valid → ✅
  │            │            │        ├─ Store claims: exchange.put("upiId", value)
  │            │            │        └─ Forward request to downstream service
```

### Arrow annotations:
- Green arrows for success paths
- Red arrows (stroke #e03131) for 401 rejection paths
- Each 401 response includes label: `401 UNAUTHORIZED`

---

## Rate Limiting

Generate an Excalidraw diagram JSON for **Rate Limiting** in RevPay.

**Elements**: `Redis`, `→`

**Layout** (flow diagram):

### Token Bucket Algorithm:
```
Request arrives → KeyResolver: extract Client IP
  │
  └─→ Redis Token Bucket
       │
       ├─ Bucket capacity: burstCapacity (e.g., 40)
       ├─ Replenish rate: replenishRate per second (e.g., 20)
       │
       ├─ Available tokens > 0?
       │   ├─ YES → consume 1 token → forward request ✅
       │   └─ NO  → 429 Too Many Requests 🔴
```

### Per-route limits table (below):
```
Route               | Rate          | Burst
────────────────────┼───────────────┼───────
/users/**           | 20 req/s      | 40
/wallet/**          | 20 req/s      | 40
/transactions/**    | 10 req/s      | 20    (payments are expensive)
```

### Arrow from each route to Redis showing:
`KeyResolver(Client IP) → Redis: INCR rate_limit:<ip>:<route>` 

---

# SYSTEM DESIGN INTERVIEW QUESTIONS

## Q1: How Does RevPay Prevent Double Charges?

Generate an Excalidraw diagram JSON for **Q1: How Does RevPay Prevent Double Charges?** in RevPay.

**Elements**: `Redis`, `GET`, `→`

**Layout** (three layers stacked vertically):

### Layer 1: Idempotency Key (Redis)
```
Client → Transaction Service: POST /transactions/pay {requestId: "uuid-123"}
Transaction Service → Redis: GET idempotency:uuid-123
Redis → Transaction Service: (nil) — first time
→ Proceed with payment
```
If replayed: `Redis returns cached txnId → return cached response IMMEDIATELY → skip processing`

### Layer 2: Unique Constraint (PostgreSQL)
```
Even if Redis is down:
  Thread A: INSERT INTO transactions (request_id='uuid-123') → OK
  Thread B: INSERT INTO transactions (request_id='uuid-123') → FAIL (unique violation)
  → Catch → SELECT by request_id → return existing result
```
Arrow: `request_id column = UNIQUE constraint`

### Layer 3: @Version Optimistic Locking (Wallet)
```
Even if transfer is retried:
  First attempt: UPDATE wallets SET balance=... WHERE version=1 → success (version→2)
  Second attempt: UPDATE wallets SET balance=... WHERE version=1 → fails (0 rows)
  → OptimisticLockException → retry → reads new balance → correct
```

### Bottom summary:
```
Three independent defense layers:
  1. Redis idempotency (fast path)
  2. PostgreSQL unique constraint (fallback)
  3. @Version optimistic locking (last resort)
```

---

## Q2: What Happens If Redis Goes Down?

Generate an Excalidraw diagram JSON for **Q2: What Happens If Redis Goes Down?** in RevPay.

**Elements**: `Redis`, `JWT`, `DB`, `→`

**Layout** (three impact areas side by side):

### Impact 1: Idempotency
```
Normal: Transaction Service → Redis: GET idempotency:<key>
Redis DOWN → fallback:
  Transaction Service → PostgreSQL: findByRequestId(requestId)
```
Arrow labels: `Redis GET (normal, ~0.1ms)` → `DB query (fallback, ~5ms)`

### Impact 2: Rate Limiting
```
Normal: Gateway → Redis: token bucket
Redis DOWN → token buckets all empty → ALL requests pass through (no rate limiting)
```
Red warning: "Rate limiting disabled until Redis recovers"

### Impact 3: JWT Auth — UNAFFECTED
```
Gateway: Keys.hmacShaKeyFor(jwt.secret.getBytes())
         → JWT validation uses LOCAL secret key → no Redis dependency
```
Green check: "✅ No impact — JWT auth is offline"

### Recovery box (bottom):
```
Redis is stateless for idempotency:
  - Keys have TTL → no data loss
  - Redis comes back → new keys created fresh
  - Rate limiting restarts with full buckets

No permanent data loss in any scenario.
```

---

## Q3: How Would You Scale to 1 Million Transactions Per Day?

Generate an Excalidraw diagram JSON for **Q3: How Would You Scale RevPay?** in RevPay.

**Elements**: `Redis`, `DB`, `→`

**Layout** (four strategy panels):

### Panel 1: Horizontal Scaling
```
Load Balancer (ALB)
  ├──→ Transaction Service Instance 1
  ├──→ Transaction Service Instance 2
  ├──→ Transaction Service Instance 3
  └──→ Transaction Service Instance N
```
Label: "Each service is stateless → scale horizontally"

### Panel 2: Database Sharding
```
Shard 1: transactions where hash(sender_upi) % N = 0
Shard 2: transactions where hash(sender_upi) % N = 1
Shard 3: transactions where hash(sender_upi) % N = 2
```
Arrow from "PostgreSQL" branching into 3 "Shard" boxes

### Panel 3: Kafka Partitioning
```
Topic: txn.completed
  ├── Partition 0 → Consumer 1
  ├── Partition 1 → Consumer 2
  ├── Partition 2 → Consumer 3
  └── Partition 3 → Consumer 4
```
Label: "More partitions = more parallel consumers"

### Panel 4: Caching
```
Redis cache:
  ├── Cache daily fraud limits → INCR daily:alice@miniupi:2024-03-15
  │     (avoids SUM query on every payment)
  └── Cache user profiles → GET user:alice@miniupi
        (avoids hitting user-service for every lookup)
```
Label: "Use TTL for bounded memory"

---

Generate the complete Excalidraw JSON for each prompt above, importable directly into `excalidraw.com`.
