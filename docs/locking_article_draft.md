# 🚀 Scaling a UPI Wallet Service: Why We Dropped Pessimistic Locks for Optimistic Locking

If you are building a financial system, a digital wallet, or a payment gateway, there is one nightmare scenario that keeps every developer awake at night: **The Lost Update**.

Imagine a user has a wallet balance of ₹100. In the exact same millisecond, two friends send them ₹50 and ₹20. 
- Thread A reads the balance (₹100).
- Thread B reads the balance (₹100).
- Thread A adds ₹50 and saves the new balance (₹150).
- Thread B adds ₹20 (to its initial read of ₹100) and saves the new balance (₹120).

Thread B just overwrote Thread A's transaction. The user should have ₹170, but they only have ₹120. ₹50 has vanished into the digital void.

When building my own high-throughput Mini-UPI wallet service, I had to solve this exact concurrency problem. Here is a look at the two paths I explored: **Pessimistic Locking** and **Optimistic Locking**, and why one clearly wins out for a high-TPS (Transactions Per Second) system.

---

## 🔒 Approach 1: Pessimistic Locking ("Assuming the Worst")

My first instinct was to use Pessimistic Locking. This approach assumes that a collision *will* happen, so it takes drastic measures to prevent it.

When a thread wants to update a wallet, it places a hard lock on the database row as soon as it reads it. In Spring Boot / JPA, this looks like this:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.upiId = :upiId")
Optional<Wallet> findByUpiIdForUpdate(String upiId);
```

Under the hood, JPA executes a `SELECT ... FOR UPDATE`. 

### The Problem? It Kills Throughput.
While Pessimistic Locking is 100% safe, it is terrible for performance. If a popular vendor receives 1,000 payments in a second, 1 thread gets the lock, and 999 threads are blocked, waiting in a queue. 

This causes massive bottlenecks, exhausts database connection pools, and spikes API latency. For a high-throughput UPI service, this is unacceptable.

---

## ⚡ Approach 2: Optimistic Locking ("Assuming the Best")

Enter **Optimistic Locking**. This approach assumes that collisions are actually quite rare. Instead of locking the database row, it relies on a `version` number.

Every time the row is updated, the version increments. If a thread tries to update a row using a stale version number, the database rejects the update.

Implementing this in JPA is beautiful. You just add a `@Version` field to your entity:

```java
@Entity
public class Wallet {
    // ... other fields (balance, upiId)

    @Version
    private Long version;
}
```

### How it solves the Lost Update:
1. **Thread A** and **Thread B** both read the wallet: Balance = ₹100, **Version = 0**.
2. **Thread A** adds ₹50. The SQL looks like this:
   `UPDATE wallets SET balance = 150, version = 1 WHERE id = 'xyz' AND version = 0`
   The database version is updated to `1`.
3. **Thread B** tries to add ₹20:
   `UPDATE wallets SET balance = 120, version = 1 WHERE id = 'xyz' AND version = 0`
   Because the database version is now `1`, the `WHERE version = 0` condition fails. Zero rows are updated!

JPA realizes zero rows were updated and immediately throws an **`OptimisticLockException`**. 

### Handling the Exception
Instead of crashing, our service simply catches this exception, re-fetches the latest balance (₹150), and retries adding the ₹20.

---

## 🏆 The Verdict

By switching from Pessimistic to Optimistic Locking, our wallet service removed a massive database bottleneck. 

Millions of read and write requests can now flow through the system simultaneously without waiting on row-level database locks. We only pay a slight performance penalty (the retry) in the incredibly rare instance that two threads touch the exact same user's wallet at the exact same millisecond.

If you are building a high-throughput financial system, don't default to locking down your database. Be optimistic!

*(If you found this helpful, let me know in the comments how you handle concurrency in your microservices!)*
