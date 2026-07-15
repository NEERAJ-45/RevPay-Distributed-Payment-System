# RevPay CI/CD — Getting It Live on AWS EC2 Free Tier (Detailed Guide)

> **How to read this guide:** Every step explains _what_ to do, _why_ it matters, and _what you should see_ when it works. Follow the steps **in order** — each section depends on the previous one.

---

## Overview — How It All Fits Together

Before you start, here is the high-level picture of what you are building:

![RevPay CI/CD overview](deploy-flow.png)

_Diagram: CI/CD flow (push → GitHub Actions → build & push images → deploy to EC2 running infrastructure & microservices)._ 

**Key constraint:** AWS Free Tier `t2.micro` only has **1 GB RAM**. The bootstrap script compensates with a 4 GB swap file so Docker can run all 13+ containers.

---

## STEP 1 — Provision the AWS EC2 Instance

### What EC2 is
EC2 (Elastic Compute Cloud) is AWS's virtual machine service. You are renting a `t2.micro` server — a small Linux VM with 1 vCPU, 1 GB RAM, and up to 30 GB of storage (on EBS). This is the machine that will run all your Docker containers 24/7.

### Steps

1. In the AWS Console, use the search bar at the top to find **EC2**, then click **EC2** in the results.
2. On the EC2 Dashboard, click the orange **Launch instance** button.
3. Fill in the configuration:

   **Name:**
   ```
   revpay-server
   ```

   **Application and OS Images (AMI):**
   - Click **Ubuntu** from the quick-select row.
   - In the dropdown below, choose: **Ubuntu Server 24.04 LTS (HVM), SSD Volume Type**
   - Architecture: **64-bit (x86)**

   > [!NOTE]
   > Ubuntu 24.04 LTS (codename `noble`) is now supported by Docker APT repositories. You can safely select it.

   **Instance type:**
   - Choose **`t2.micro`** — it shows "Free tier eligible" next to it.
   - If `t2.micro` is unavailable in your chosen region, choose **`t3.micro`** — also Free Tier eligible.

   **Key pair (login):**
   - Click **Create new key pair**.
   - Key pair name: `revpay-ec2-key`
   - Key pair type: `RSA`
   - Private key file format: `.pem` (used with OpenSSH — works on Linux, macOS, and Windows PowerShell)
   - Click **Create key pair** — your browser will download `revpay-ec2-key.pem` automatically.
   - ⚠️ **Save this file safely.** AWS will never give you the private key again.

   **Network settings:**
   - Leave the default VPC and subnet.
   - Ensure **Auto-assign public IP** is set to **Enable**.
   - Leave the firewall/security group as the default for now — you will customize it in the next step.

   **Configure storage:**
   - Keep the default 8 GB root volume. Free Tier allows up to 30 GB — you can increase to 20–30 GB for extra headroom.

4. Click **Launch instance** (bottom right).

5. Wait 1–2 minutes. Refresh the **Instances** page until the **Instance state** column shows **Running** and the **Status check** column shows **2/2 checks passed**.

6. Click on your instance row. In the details panel below, copy the **Public IPv4 address** (looks like `54.x.x.x`). You will need this in nearly every subsequent step.

---

## STEP 2 — Open Security Group Firewall Ports in AWS

### Why this step is necessary
AWS wraps every EC2 instance in a **Security Group** — a stateful firewall that blocks all inbound traffic by default. Docker can expose ports on the VM, but those ports are invisible to the outside world until the Security Group explicitly allows traffic.

You need to open two categories of ports:
- **Port 22 (SSH)** — so you can connect to the VM to manage it.
- **Ports 80, 8080–8084** — so external clients can reach Nginx and the microservices.

### Steps

1. On the EC2 instance details page, click the **Security** tab.
2. Under **Security groups**, click the link to your security group (e.g., `launch-wizard-1`).
3. On the Security Group page, click the **Inbound rules** tab → **Edit inbound rules**.
4. Click **Add rule** for each row in the table below:

| Type | Protocol | Port Range | Source | Description |
|---|---|---|---|---|
| SSH | TCP | `22` | `0.0.0.0/0` | Remote SSH login |
| HTTP | TCP | `80` | `0.0.0.0/0` | Nginx reverse proxy |
| Custom TCP | TCP | `8080` | `0.0.0.0/0` | API Gateway |
| Custom TCP | TCP | `8081` | `0.0.0.0/0` | User Service |
| Custom TCP | TCP | `8082` | `0.0.0.0/0` | Wallet Service |
| Custom TCP | TCP | `8083` | `0.0.0.0/0` | Transaction Service |
| Custom TCP | TCP | `8084` | `0.0.0.0/0` | Notification Service |

5. Click **Save rules**.

> [!NOTE]
> Source `0.0.0.0/0` means "allow from any IPv4 address." For a production system you would restrict SSH to your office IP — but for learning this is fine.

> [!TIP]
> There is also an OS-level firewall (UFW) inside Ubuntu. The EC2 bootstrap script (Step 4) handles this automatically — you do **not** need to configure UFW manually.

---

## STEP 3 — Fix SSH Key Permissions & Connect to the VM

### Why key permissions matter
SSH enforces strict security: if your `.pem` file is readable by other users on the system, `ssh` will refuse to use it and display a "Permissions are too open" error. You must restrict the file to owner-read-only.

### Fix permissions

**Windows (PowerShell — run as Administrator):**
```powershell
# This removes all inherited permissions and grants only your user read access
icacls "$env:USERPROFILE\Downloads\revpay-ec2-key.pem" /inheritance:r /grant:r "$($env:USERNAME):R"
```

**macOS / Linux (Bash):**
```bash
chmod 400 ~/Downloads/revpay-ec2-key.pem
```

### Connect to the VM
```bash
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
```

Replace `<YOUR_EC2_PUBLIC_IP>` with the IP you copied in Step 1 (e.g., `54.187.22.101`).

**First connection prompt:**
```
The authenticity of host '54.x.x.x (54.x.x.x)' can't be established.
ED25519 key fingerprint is SHA256:xxxx...
Are you sure you want to continue connecting (yes/no/[fingerprint])?
```
Type `yes` and press Enter. You will now see the Ubuntu shell prompt:
```
ubuntu@ip-172-31-x-x:~$
```

> [!TIP]
> To avoid typing the long SSH command every time, add this to your `~/.ssh/config` on your local machine:
> ```
> Host revpay
>     HostName 54.x.x.x
>     User ubuntu
>     IdentityFile ~/Downloads/revpay-ec2-key.pem
> ```
> Then just run `ssh revpay` to connect.

---

## STEP 4 — Run the EC2 Bootstrap Script

### What the script does
The bootstrap script `infra/ec2-setup.sh` fully prepares the VM. Here is a breakdown of each of its 7 sections:

| Section | What it does | Why |
|---|---|---|
| 1. System update | `apt-get update && upgrade` | Applies security patches |
| 2. **Swap space** | Creates a 4 GB `/swapfile` | **Critical:** Prevents OOM crashes on 1 GB RAM |
| 3. Docker install | Adds Docker's official APT repo; installs `docker-ce`, `docker-compose-plugin` | Needed to run containers |
| 4. UFW firewall | Opens ports 22, 80, 443, 8080–8084 | OS-level firewall to match AWS security group |
| 5. App directory | Creates `/opt/revpay/` owned by `ubuntu` user | Safe home for compose files and secrets |
| 6. `.env` template | Creates `/opt/revpay/.env` with `REPLACE_ME` placeholders | Secrets are never in the git repo |
| 7. Docker network | Creates a bridge network named `upi-net` | All containers communicate on this internal network |

### Why swap is the most critical part
A `t2.micro` has 1 GB RAM. The project runs 13 containers. Even with Spring Boot's `-Xmx192m` JVM memory cap, RAM fills up. Without swap, the Linux OOM (Out Of Memory) killer randomly terminates containers, causing cascading failures. The 4 GB swap file uses the EBS disk as overflow memory — it's slower than RAM but prevents crashes entirely.

### Run the bootstrap from your local machine

> [!IMPORTANT]
> Run this command from your **local machine** (where the project is cloned), **not** from inside the VM. The `< infra/ec2-setup.sh` syntax streams the local file over SSH as standard input and runs it as a shell script on the remote machine.

```bash
# Make sure you are in the project root directory first
cd <your-local-project-directory>

# Stream and execute the bootstrap script on the EC2 VM
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP> 'bash -s' \
  < infra/ec2-setup.sh
```

**Expected output (abbreviated):**
```
▶ Detected Ubuntu / Debian (apt-get) for AWS EC2 instance
▶ Updating system packages...
...
▶ Setting up 4 GB Swap space to prevent Out Of Memory crashes...
✅ 4 GB Swap Space successfully enabled!
▶ Installing Docker...
✅ Docker installed — you may need to re-login for group change to take effect
▶ Opening firewall ports (OS-level UFW)...
✅ UFW firewall active and rules applied.
▶ Creating /opt/revpay directory...
▶ Creating /opt/revpay/.env template...
✅ /opt/revpay/.env created — fill in the REPLACE_ME values before running compose.
▶ Creating Docker network 'upi-net'...
✅ upi-net created
════════════════════════════════════════════════════════════
  AWS EC2 Bootstrap Complete! Next Steps:
...
════════════════════════════════════════════════════════════
```

### Re-login after the script finishes

The bootstrap script adds the `ubuntu` user to the `docker` group. This change only takes effect in **new shell sessions** — your current SSH session still requires `sudo` for Docker commands.

```bash
# Disconnect
exit

# Reconnect — the docker group membership is now active
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
```

### Verify swap is active
```bash
# Should show a line: /swapfile  file  4.0G  0B  -2
swapon -s

# Should show total memory ≈ 1 GB + 4 GB swap
free -h
```

Expected output of `free -h`:
```
               total        used        free      shared  buff/cache   available
Mem:           976Mi       ...Mi       ...Mi       ...Mi       ...Mi       ...Mi
Swap:          4.0Gi         0B       4.0Gi
```

---

## STEP 5 — Fill In the Secrets File on the VM

### Why secrets go in `.env`, not in the repo
The `.env` file holds database passwords, JWT signing keys, and other credentials. Committing these to git — even in a "private" repo — is a serious security risk. The bootstrap script created `/opt/revpay/.env` with placeholder values; you now fill in the real secrets directly on the VM.

### Generate strong secrets first
Run these commands on the VM to generate cryptographically secure random values:
```bash
# Generate a strong password (for Postgres and Redis)
openssl rand -base64 24

# Generate a JWT signing key (must be ≥ 256 bits / 32 bytes for HS256)
openssl rand -hex 64
```
Run each command once per secret and copy the output.

### Edit the secrets file
```bash
# Open the .env file in the nano text editor
nano /opt/revpay/.env
```

Replace every `REPLACE_ME` value with what you generated:

```env
# ── Database ─────────────────────────────────────────────────────────
POSTGRES_USER=revpay
POSTGRES_PASSWORD=<output of: openssl rand -base64 24>
POSTGRES_HOST=postgres
POSTGRES_PORT=5432

# ── Redis ─────────────────────────────────────────────────────────────
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<output of: openssl rand -base64 24>

# ── Kafka ─────────────────────────────────────────────────────────────
KAFKA_BOOTSTRAP_SERVERS=kafka:29092

# ── JWT ───────────────────────────────────────────────────────────────
JWT_SECRET=<output of: openssl rand -hex 64>
JWT_EXPIRY_MS=86400000

# ── Service Ports ─────────────────────────────────────────────────────
USER_SERVICE_PORT=8081
WALLET_SERVICE_PORT=8082
TRANSACTION_SERVICE_PORT=8083
NOTIFICATION_SERVICE_PORT=8084
GATEWAY_PORT=8080
```

**Save and exit nano:** Press `Ctrl+O` → `Enter` (to save) → `Ctrl+X` (to exit).

### Verify the file is locked down
```bash
ls -la /opt/revpay/.env
```

Expected output (must show `-rw-------`):
```
-rw------- 1 ubuntu ubuntu 512 Jun  1 14:00 /opt/revpay/.env
```

If it shows anything else (e.g., `-rw-r--r--`), fix it:
```bash
chmod 600 /opt/revpay/.env
```

> [!CAUTION]
> Do not use the same password for Postgres and Redis — if one service is compromised, the other remains protected. Use separate randomly-generated values.

---

## STEP 6 — Log In to GHCR on the VM

### What GHCR is and why you need to log in
GHCR (GitHub Container Registry) is where your CI/CD pipeline pushes the Docker images it builds (e.g., `ghcr.io/your-username/revpay-user-service:sha-abc1234`). Even though the images are in a public repo, Docker requires authentication to pull from GHCR. You authenticate using a **Personal Access Token (PAT)** — not your GitHub password.

### Create the PAT (do this in your browser)
1. On GitHub, click your **profile icon** (top right) → **Settings**.
2. Scroll down in the left sidebar → **Developer settings** → **Personal access tokens** → **Tokens (classic)**.
3. Click **Generate new token (classic)**.
4. Fill in the form:
   - **Note:** `revpay-ec2-pull`
   - **Expiration:** `No expiration` *(for a learning project — for production, use 90 days and rotate)*
   - **Scopes:** Check only **`read:packages`** — this is the minimum permission needed to pull images.
5. Click **Generate token**.
6. **Copy the token immediately** — GitHub shows it only once. It looks like `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`.

### Log in on the VM
```bash
# Pipe the token directly into docker login — avoids it appearing in shell history
echo "<YOUR_PAT_TOKEN>" | docker login ghcr.io -u <YOUR_GITHUB_USERNAME> --password-stdin
```

Expected output:
```
WARNING! Your password will be stored unencrypted in /home/ubuntu/.docker/config.json.
Configure a credential helper to remove this warning. See
https://docs.docker.com/engine/reference/commandline/login/#credentials-store

Login Succeeded
```

> [!NOTE]
> The "unencrypted" warning is expected — for a single VM used by one person, this is acceptable. On a production multi-user system, you would configure a credential store.

### Verify the login worked
```bash
cat ~/.docker/config.json
```

You should see `ghcr.io` listed under `auths`:
```json
{
  "auths": {
    "ghcr.io": {
      "auth": "..."
    }
  }
}
```

---

## STEP 7 — Copy docker-compose.yml and Start Infrastructure

### What you are doing in this step
The `docker-compose.yml` defines all your infrastructure services. The CI/CD pipeline will eventually deploy the microservices (user-service, wallet-service, etc.) — but the **infrastructure layer** (Postgres, Redis, Kafka, Nginx, monitoring) must be running first, and must be started manually. These services are long-lived and stateful — they do not get redeployed on every CI/CD run.

### Copy files from your local machine to the VM

```bash
# From your LOCAL machine — run these commands in your project root

# Copy the main compose file
scp -i ~/Downloads/revpay-ec2-key.pem \
  docker-compose.yml ubuntu@<YOUR_EC2_PUBLIC_IP>:/opt/revpay/

# Copy the entire infra directory (nginx config, prometheus config, grafana provisioning, init-db.sql)
scp -i ~/Downloads/revpay-ec2-key.pem -r \
  infra/ ubuntu@<YOUR_EC2_PUBLIC_IP>:/opt/revpay/
```

The `-r` flag in the second command copies the directory recursively. The `infra/` directory includes:
- `nginx/nginx.conf` — reverse proxy routing rules
- `prometheus/prometheus.yml` — metrics scraping config
- `grafana/provisioning/` — pre-built dashboards
- `init-db.sql` — creates the 4 PostgreSQL databases (`users_db`, `wallets_db`, `transactions_db`, `notifications_db`)

### SSH into the VM and start the infrastructure

```bash
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
cd /opt/revpay
```

The `docker-compose.yml` file has a top-level `version: "3.9"` key that newer versions of Docker Compose print a warning about (it's been deprecated). Remove it before starting:

```bash
# Remove the 'version:' line from docker-compose.yml
sed -i '/^version:/d' docker-compose.yml
```

Now start only the **infrastructure services** — the microservices are intentionally excluded because CI/CD will manage those:

```bash
docker compose --env-file .env up -d \
  postgres redis zookeeper kafka kafka-ui nginx prometheus grafana zipkin
```

**What each service does:**
| Service | Image | Port | Role |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | 5432 | Stores all 4 microservice databases |
| `redis` | `redis:7.2-alpine` | 6379 | Session/token cache + pub-sub |
| `zookeeper` | `confluentinc/cp-zookeeper:7.6.0` | 2181 | Kafka cluster coordinator |
| `kafka` | `confluentinc/cp-kafka:7.6.0` | 9092/29092 | Async event streaming between services |
| `kafka-ui` | `provectuslabs/kafka-ui:latest` | 8090 | Browser-based Kafka topic inspector |
| `nginx` | `nginx:1.25-alpine` | 80 | HTTP reverse proxy / load balancer |
| `prometheus` | `prom/prometheus:v2.51.2` | 9090 | Metrics scraping from actuator endpoints |
| `grafana` | `grafana/grafana:10.4.2` | 3000 | Dashboards (default: admin/admin) |
| `zipkin` | `openzipkin/zipkin:3` | 9411 | Distributed tracing UI |

### Verify all services started
```bash
docker compose --env-file .env ps
```

Expected output — all listed containers should show `Up`:
```
NAME              IMAGE                               STATUS          PORTS
upi-grafana       grafana/grafana:10.4.2              Up              0.0.0.0:3000->3000/tcp
upi-kafka         confluentinc/cp-kafka:7.6.0         Up (healthy)    0.0.0.0:9092->9092/tcp
upi-kafka-ui      provectuslabs/kafka-ui:latest       Up              0.0.0.0:8090->8080/tcp
upi-nginx         nginx:1.25-alpine                   Up (healthy)    0.0.0.0:80->80/tcp
upi-postgres      postgres:16-alpine                  Up (healthy)    0.0.0.0:5432->5432/tcp
upi-prometheus    prom/prometheus:v2.51.2             Up              0.0.0.0:9090->9090/tcp
upi-redis         redis:7.2-alpine                    Up (healthy)    0.0.0.0:6379->6379/tcp
upi-zipkin        openzipkin/zipkin:3                 Up              0.0.0.0:9411->9411/tcp
upi-zookeeper     confluentinc/cp-zookeeper:7.6.0    Up              0.0.0.0:2181->2181/tcp
```

> [!TIP]
> If Kafka takes a few minutes to show `(healthy)` that is normal — it waits for Zookeeper to be fully ready first. Wait 60 seconds and re-run `docker compose --env-file .env ps`.

### Quick smoke test

Verify Postgres created the databases:
```bash
docker exec upi-postgres psql -U revpay -c '\l'
```

Expected output should include:
```
   Name        | Owner  | ...
---------------+--------+
 notifications_db | revpay | ...
 transactions_db  | revpay | ...
 users_db         | revpay | ...
 wallets_db       | revpay | ...
```

---

## STEP 8 — Add GitHub Secrets (AWS EC2)

### What GitHub Secrets are
GitHub Secrets are encrypted environment variables stored in your repository's settings. They are injected into your GitHub Actions workflow YAML as `${{ secrets.SECRET_NAME }}`. They are **never** shown in logs — GitHub automatically masks them.

The `deploy` job in each workflow uses `appleboy/ssh-action` to SSH into your EC2 VM and run deployment commands. It needs:
- The VM's IP address and username to connect
- Your private SSH key to authenticate
- The same secret values that are in `/opt/revpay/.env` (so it can pass them as container environment variables or verify them)

### Where to add secrets
Go to: **Your GitHub repo → Settings → Secrets and variables → Actions → New repository secret**

Add each one individually:

| Secret Name | Value | How to get it |
|---|---|---|
| `EC2_HOST` | Your AWS EC2 Public IPv4 address (e.g. `54.x.x.x`) | EC2 Console → Instance details |
| `EC2_USER` | `ubuntu` | Fixed — Ubuntu AMI default user |
| `EC2_SSH_KEY` | **Full contents** of `revpay-ec2-key.pem` | Open the `.pem` file in a text editor and paste everything including `-----BEGIN RSA PRIVATE KEY-----` and `-----END RSA PRIVATE KEY-----` |
| `POSTGRES_USER` | Same value as in `/opt/revpay/.env` | What you put in Step 5 |
| `POSTGRES_PASSWORD` | Same value as in `/opt/revpay/.env` | What you put in Step 5 |
| `REDIS_PASSWORD` | Same value as in `/opt/revpay/.env` | What you put in Step 5 |
| `JWT_SECRET` | Same value as in `/opt/revpay/.env` | What you put in Step 5 |

> [!CAUTION]
> The `EC2_SSH_KEY` secret must be the **entire** private key file content. Include all header/footer lines. If you only paste the base64 blob without the `-----BEGIN...-----` lines, SSH authentication will fail.

### How to read the `.pem` file on Windows PowerShell
```powershell
Get-Content "$env:USERPROFILE\Downloads\revpay-ec2-key.pem" | Set-Clipboard
```
This copies the entire file contents to your clipboard — then paste it into the GitHub secret field.

---

## STEP 9 — Create the `production` GitHub Environment

### Why this is necessary
The `deploy` job in each workflow YAML has this line:
```yaml
environment: production
```

GitHub uses **Environments** to gate deployments. If the `production` environment does not exist, the deploy job will be blocked with an error like: _"Could not find environment: production"_.

Additionally, environments allow you to add **required reviewers** — meaning a deploy cannot proceed until a human approves it in the GitHub UI. This is useful for controlling what goes live.

### Steps
1. Go to: **GitHub repo → Settings → Environments → New environment**
2. Name: `production`
3. (Recommended for learning) Under **Deployment protection rules** → **Required reviewers** → add your own GitHub username → click **Save protection rules**.
   - This means you will get an email + GitHub notification every time a deploy is about to run, and you must click Approve for it to proceed.
   - You can skip this if you want deploys to run automatically without approval.
4. Click **Configure environment** / Save.

---

## STEP 10 — Trigger Your First Deploy

### How the path filters work
Each of the 5 workflows only fires when specific paths change. For example, `ci-user-service.yml` has:

```yaml
on:
  push:
    branches: [master]
    paths:
      - 'user-service/**'
      - 'common/**'
```

This means: **only** run this pipeline if a push to `master` touches a file inside `user-service/` or `common/`. This is a deliberate optimization — you don't want all 5 pipelines to rebuild and redeploy when only one service changed.

### Trigger user-service pipeline

```bash
# Run from your local machine, inside the project root
cd <your-local-project-directory>

# Add a blank line to application.properties to trigger the path filter
echo "" >> user-service/src/main/resources/application.properties

# Stage and commit
git add user-service/src/main/resources/application.properties
git commit -m "ci: trigger first AWS EC2 deploy for user-service"

# Push to master — this fires the CI/CD pipeline
git push origin master
```

### What happens next (the full pipeline flow)
After the push, GitHub receives the event and starts your workflow. Here is the exact sequence:

```
Push to master (user-service/** changed)
│
├─► Job 1: Build & Test (~3-4 min)
│     ├── Checkout code
│     ├── Set up JDK 21 (Temurin)
│     ├── ./mvnw clean verify -pl common,user-service
│     │     └── Testcontainers spins up real Postgres + Kafka in Docker
│     │         (runs inside the GitHub Actions runner VM)
│     └── Upload Surefire test reports (saved for 7 days)
│
├─► Job 2: Docker Build & Push (~2-3 min)  [only runs if Job 1 passes]
│     ├── Checkout code
│     ├── Log in to GHCR with GITHUB_TOKEN
│     ├── Extract image tags (sha-abc1234, latest)
│     ├── Set up Docker Buildx (enables layer caching)
│     └── Build multi-stage Dockerfile + push to ghcr.io/you/revpay-user-service
│
└─► Job 3: Deploy to AWS EC2 (~1-2 min)  [only runs if Job 2 passes]
      ├── SSH into EC2 VM (using EC2_HOST, EC2_USER, EC2_SSH_KEY secrets)
      ├── docker pull ghcr.io/you/revpay-user-service:sha-abc1234
      ├── Record previous container's image (for rollback)
      ├── docker stop user-service && docker rm user-service
      ├── docker run -d --name user-service --network upi-net \
      │     --memory=256m -p 8081:8081 --env-file /opt/revpay/.env \
      │     ghcr.io/you/revpay-user-service:sha-abc1234
      ├── Health check loop (6 × 5 sec = up to 30 sec wait)
      │     └── curl http://localhost:8081/actuator/health → {"status":"UP"}
      ├── If health check FAILS → auto-rollback to previous image
      └── docker image prune (removes images older than 72 hours)
```

### Watch it run
1. Go to your repository on GitHub.
2. Click the **Actions** tab.
3. You will see **CI/CD — User Service** running with a yellow spinning indicator.
4. Click on it to see the live log output for each job.

---

## STEP 11 — Verify Everything On the VM

After the pipeline completes (green checkmarks on all 3 jobs), SSH into the VM and confirm everything is working end-to-end.

```bash
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
```

### Check 1: Is the container running?
```bash
docker ps | grep user-service
```

Expected output:
```
abc123def456   ghcr.io/you/revpay-user-service:sha-abc1234   "java -jar app.jar"   2 minutes ago   Up 2 minutes   0.0.0.0:8081->8081/tcp   user-service
```

### Check 2: Does the health endpoint return UP?
```bash
curl -s http://localhost:8081/actuator/health | python3 -m json.tool
```

Expected output:
```json
{
    "status": "UP",
    "components": {
        "db": {
            "status": "UP",
            "details": { ... }
        },
        "diskSpace": {
            "status": "UP"
        },
        "redis": {
            "status": "UP"
        }
    }
}
```

### Check 3: Is it accessible from the internet through Nginx?
```bash
# Run this from your local machine (or any machine)
curl http://<YOUR_EC2_PUBLIC_IP>/actuator/health
```

If Nginx is correctly proxying requests to `user-service`, you should get the same `{"status":"UP"}` response.

### Check 4: Memory usage
```bash
# Check how much RAM the containers are using collectively
docker stats --no-stream
```

All microservice containers should be using ≤256 MB each (enforced by `--memory=256m` in the deploy step). The infrastructure containers use what they need. If total memory usage is high, verify swap is still active with `free -h`.

### Check 5: View container logs
```bash
# See the last 50 lines of user-service logs
docker logs --tail 50 user-service

# Follow live logs (Ctrl+C to stop)
docker logs -f user-service
```

---

## STEP 12 — Deploy the Remaining Services

Repeat Step 10 for each of the remaining 4 services. Each service has its own workflow file and its own path filter.

```bash
# Trigger wallet-service
echo "" >> wallet-service/src/main/resources/application.properties
git add wallet-service/src/main/resources/application.properties
git commit -m "ci: trigger first AWS EC2 deploy for wallet-service"
git push origin master

# Wait for wallet-service pipeline to complete, then:

# Trigger transaction-service
echo "" >> transaction-service/src/main/resources/application.properties
git add transaction-service/src/main/resources/application.properties
git commit -m "ci: trigger first AWS EC2 deploy for transaction-service"
git push origin master

# Trigger notification-service
echo "" >> notification-service/src/main/resources/application.properties
git add notification-service/src/main/resources/application.properties
git commit -m "ci: trigger first AWS EC2 deploy for notification-service"
git push origin master

# Trigger api-gateway
echo "" >> api-gateway/src/main/resources/application.properties
git add api-gateway/src/main/resources/application.properties
git commit -m "ci: trigger first AWS EC2 deploy for api-gateway"
git push origin master
```

> [!TIP]
> You can also trigger a workflow manually without making a code change: go to **Actions → [Workflow Name] → Run workflow** (top right). All workflows have `workflow_dispatch:` enabled.

---

## STEP 13 — Verify the Full System End-to-End

Once all 5 services are deployed, verify the complete system:

```bash
# All containers running?
docker ps

# Check each service health
for port in 8080 8081 8082 8083 8084; do
  echo -n "Port $port: "
  curl -s http://localhost:$port/actuator/health | grep -o '"status":"[^"]*"' || echo "NO RESPONSE"
done
```

### Access the monitoring UIs

Open these in your browser (replace with your EC2 IP):

| Service | URL | Credentials |
|---|---|---|
| **Grafana** | `http://<EC2_IP>:3000` | admin / admin *(change on first login)* |
| **Prometheus** | `http://<EC2_IP>:9090` | None |
| **Zipkin** | `http://<EC2_IP>:9411` | None |
| **Kafka UI** | `http://<EC2_IP>:8090` | None |

> [!NOTE]
> Ports 3000, 9090, 9411, and 8090 are not open in your AWS Security Group by default (you only opened 22, 80, 8080–8084). To access these UIs, either open additional Security Group rules for your IP only, or use SSH port forwarding:
> ```bash
> # Forward Grafana to localhost:3000 via SSH tunnel
> ssh -i ~/Downloads/revpay-ec2-key.pem -L 3000:localhost:3000 ubuntu@<EC2_IP>
> # Then open http://localhost:3000 in your browser
> ```

---

## Common Failure Points & Fixes

| Symptom | Root Cause | Fix |
|---|---|---|
| `Deploy` job: `SSH connection refused` | AWS Security Group port 22 not open | Re-check Step 2 — confirm port 22 rule exists with source `0.0.0.0/0` |
| `Deploy` job: `ssh: handshake failed: ssh: unable to authenticate` | `EC2_SSH_KEY` secret is malformed | Re-paste the full `.pem` file content including header/footer lines — see Step 8 |
| `Deploy` job: `docker pull` fails with 401 | GHCR login expired on the VM | SSH into VM and re-run Step 6 login command |
| `Deploy` job: health check `FAILED` after 30 sec | Service crashed on startup | On VM run `docker logs user-service` — look for `Cannot connect to database` or `Connection refused` errors |
| VM crashes / becomes unresponsive | Out of memory | On VM run `swapon -s`. If empty, swap wasn't created — re-run Step 4 |
| `docker compose up` fails: `network upi-net not found` | Bootstrap script's `newgrp docker` block didn't complete | On VM run: `docker network create upi-net` |
| Postgres health check fails | `.env` credentials mismatch | Compare `/opt/revpay/.env` values with what you set in GitHub Secrets |
| `Could not find environment: production` | GitHub Environment not created | Complete Step 9 |
| `Workflow was not triggered` | Branch name mismatch | Workflow triggers on `master` — verify your default branch is `master` not `main` in both GitHub and your local repo |
| Services healthy but `curl <EC2_IP>/...` times out | Nginx not routing correctly | Check Nginx config with `docker exec upi-nginx nginx -t` and view logs with `docker logs upi-nginx` |
