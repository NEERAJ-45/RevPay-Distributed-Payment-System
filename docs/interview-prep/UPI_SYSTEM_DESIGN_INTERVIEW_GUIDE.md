# 💳 Mini-UPI: System Design & Interview Preparation Hub

This directory contains detailed, service-specific system design guides and interview study resources for the Mini-UPI microservices architecture. 

Each guide breaks down the core architecture, database schemas, happy-path and negative-path execution flows, code snippets, and custom prompts to generate hand-drawn style Excalidraw diagrams.

---

## 📚 Service-Specific System Design Guides

### [1. User Service & Transactional Outbox](file:///d:/Neeraj%20Surnis/Prsnl_Project/UPI/upi/docs/interview-prep/USER_SERVICE_GUIDE.md)
*   **Concepts**: JWT authentication, VPA generation, BCrypt PIN security, and the Transactional Outbox pattern.
*   **Failures Covered**: Duplicate phone numbers, database write crashes, Kafka broker downtime, and scheduler crashes.

### [2. Wallet Service & Ledger Concurrency](file:///d:/Neeraj%20Surnis/Prsnl_Project/UPI/upi/docs/interview-prep/WALLET_SERVICE_GUIDE.md)
*   **Concepts**: Double-Entry ledger audit trail, optimistic locking (`@Version`), and atomic Feign client execution.
*   **Failures Covered**: Insufficient balances, concurrent lost updates, invalid VPAs, and consumer message re-delivery.

### [3. Transaction Service & Saga Orchestrator](file:///d:/Neeraj%20Surnis/Prsnl_Project/UPI/upi/docs/interview-prep/TRANSACTION_SERVICE_GUIDE.md)
*   **Concepts**: Saga state orchestration, fraud/velocity engine checks, and Redis-backed idempotency.
*   **Failures Covered**: Duplicate requests, velocity cap violations, RPC client timeouts, and coordinator node crashes.

### [4. API Gateway Ingress Routing](file:///d:/Neeraj%20Surnis/Prsnl_Project/UPI/upi/docs/interview-prep/API_GATEWAY_GUIDE.md)
*   **Concepts**: WebFlux Netty non-blocking event loops, JWT validation at the edge, and Redis Token Bucket rate limiting.
*   **Failures Covered**: Missing credentials, API rate-limit exhaustion, backend service downtime, and Redis connection drops.

### [5. Notification Service & Asynchronous Messaging](file:///d:/Neeraj%20Surnis/Prsnl_Project/UPI/upi/docs/interview-prep/NOTIFICATION_SERVICE_GUIDE.md)
*   **Concepts**: Eventual consistency, Kafka message partitions, consumer groups, and message deduplication.
*   **Failures Covered**: Consumer message duplicate consumption, SMS gateway outages, and malformed poison pill payloads.

---

## 🎨 Diagram Study Strategy (Excalidraw)
Each guide contains a custom-tailored **Excalidraw Prompt**. Paste these prompts into an AI diagram generator (or use them as blueprints) to generate or draw the following models:
1.  **Identity & Outbox Flow**: Visualization of atomic database writes and polling schedulers.
2.  **Concurrency Conflict Timelines**: Contrast of lock states and recovery threads.
3.  **Saga Transitions**: Success and fallback state charts.
4.  **Event Loops vs Thread-per-request**: Gateway scalability models.
5.  **Deduplication Loops**: Cache checks during asynchronous event processing.
