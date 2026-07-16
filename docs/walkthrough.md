# RevPay CI/CD — Getting It Live on AWS EC2 Free Tier (Step-by-Step)

> Follow these steps **in order**. Each section depends on the previous one. This guide targets AWS EC2 (Free Tier) to keep it free, with OCI instructions kept as commented backup.

---

## STEP 1 — Merge the CI/CD Branch into Master

Your pipeline code lives on `cicd/setup`. Get it into `master` first.

```bash
# On your local machine
git checkout master
git merge cicd/setup
git push origin master
```

> [!IMPORTANT]
> The 5 per-service workflows only trigger on pushes to `master`. They won't fire until this merge happens.

---

## STEP 2 — Make the GitHub Repo Public (or Upgrade Plan)

> [!IMPORTANT]
> **GitHub Actions** = unlimited free minutes on **public repos**.  
> **GHCR** = free image storage for **public repos**.  
> On a private repo, you get 2,000 min/month free and 500MB storage.  
> For a learning project, making it public is the easiest path.

Go to: **GitHub repo → Settings → Danger Zone → Change visibility → Public**

---

<!-- 
========================================================================
BACKUP — ORACLE CLOUD STEPS (COMMENTED OUT)
========================================================================
## STEP 3 — Create an Oracle Cloud Account

1. Go to cloud.oracle.com → Start for free
2. Register with email, phone, credit card (for identity only — no charge)
3. Choose Home Region: pick ap-mumbai-1 (India) or nearest region

## STEP 4 — Provision the Oracle Cloud VM

1. Compute → Instances → Create Instance
2. Set Name: revpay-server
3. Click Change image → Select Canonical Ubuntu 22.04 LTS
4. Click Change shape: Ampere (ARM), shape VM.Standard.A1.Flex (4 OCPU, 24 GB RAM)
5. Save private key as .key file
6. Copy Public IP address

## STEP 5 — Open Firewall Ports in OCI Console

1. Networking → Virtual Cloud Networks → VCN → Security Lists → Default Security List
2. Add Ingress Rules for Ports: 22, 80, 8080-8084 (TCP, Source: 0.0.0.0/0)
========================================================================
-->

## STEP 3 — Create an AWS Account

1. Go to **[aws.amazon.com](https://aws.amazon.com/)** and click **Create an AWS Account**.
2. Complete the registration. You will need a credit/debit card for standard identity verification (AWS offers a **12-month Free Tier** with 750 free hours/month of EC2).

---

## STEP 4 — Provision the AWS EC2 Instance

1. Open the **AWS Console** and navigate to **EC2 Dashboard** → **Launch Instance**.
2. Set **Name**: `revpay-server`.
3. Under **Application and OS Images** → Choose **Ubuntu** (select **Ubuntu 22.04 LTS**, 64-bit x86).
4. Under **Instance type** → Choose **`t2.micro`** (or **`t3.micro`** if `t2.micro` is unavailable in your region). Both are Free Tier eligible.
5. Under **Key pair (login)** → Click **Create new key pair**:
   - Key pair name: `revpay-ec2-key`
   - Key pair type: `RSA`
   - Private key file format: `.pem`
   - Click **Create key pair** and download the `.pem` file to your computer.
6. Under **Network settings** → Leave default VPC settings, and ensure **Auto-assign public IP** is enabled.
7. Click **Launch Instance**. Wait 1–2 minutes for the status to show **Running**, then copy the **Public IPv4 address** of your instance.

---

## STEP 5 — Open Security Group Firewall Ports in AWS

AWS EC2 filters traffic using Security Groups. You must open the ports for Nginx and the microservices.

1. In the EC2 Instance details page, click on the **Security** tab and click on your **Security Group** (e.g., `launch-wizard-1`).
2. Click **Edit inbound rules** under **Inbound rules**.
3. Add the following rules:

| Type | Protocol | Port Range | Source | Description |
|---|---|---|---|---|
| SSH | TCP | `22` | `0.0.0.0/0` | Remote SSH login |
| HTTP | TCP | `80` | `0.0.0.0/0` | Nginx HTTP Reverse Proxy |
| Custom TCP | TCP | `8080` | `0.0.0.0/0` | API Gateway |
| Custom TCP | TCP | `8081` | `0.0.0.0/0` | User Service |
| Custom TCP | TCP | `8082` | `0.0.0.0/0` | Wallet Service |
| Custom TCP | TCP | `8083` | `0.0.0.0/0` | Transaction Service |
| Custom TCP | TCP | `8084` | `0.0.0.0/0` | Notification Service |

4. Click **Save rules**.

---

## STEP 6 — Fix SSH Key Permissions & Connect

**Windows (PowerShell as Administrator):**
```powershell
icacls "$env:USERPROFILE\Downloads\revpay-ec2-key.pem" /inheritance:r /grant:r "$($env:USERNAME):R"
```

**Connect to the VM:**
```bash
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
```

---

<!--
========================================================================
BACKUP — ORACLE CLOUD SETUP BOOTSTRAP (COMMENTED OUT)
========================================================================
## STEP 7 — Run the Bootstrap Script on the OCI VM
From your local machine, run:
ssh -i ~/Downloads/ssh-key-<date>.key ubuntu@<YOUR_OCI_PUBLIC_IP> 'bash -s' < infra/oci-setup.sh
========================================================================
-->

## STEP 7 — Run the EC2 Bootstrap Script

Since AWS Free Tier instances (`t2.micro`) only have **1 GB RAM**, running 13 containers (Spring Boot microservices + Postgres + Redis + Kafka) will cause memory exhaust crashes. 

To solve this, our EC2 bootstrap script automatically enables a **4 GB virtual memory swap file** on the SSD storage, allowing all containers to run stably.

From your **local machine** (not inside the VM), run:
```bash
# Stream the EC2 bootstrap script directly over SSH
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP> 'bash -s' \
  < infra/ec2-setup.sh
```

**Re-login after the script finishes** (so that the `docker` group membership takes effect):
```bash
exit
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
```

---

## STEP 8 — Fill In the Secrets File on the VM

```bash
# On the VM
nano /opt/revpay/.env
```

Replace every `REPLACE_ME`:

```env
POSTGRES_USER=revpay
POSTGRES_PASSWORD=<generate: openssl rand -base64 24>
POSTGRES_HOST=postgres
POSTGRES_PORT=5432

REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<generate: openssl rand -base64 24>

KAFKA_BOOTSTRAP_SERVERS=kafka:29092

JWT_SECRET=<generate: openssl rand -hex 64>
JWT_EXPIRY_MS=86400000

USER_SERVICE_PORT=8081
WALLET_SERVICE_PORT=8082
TRANSACTION_SERVICE_PORT=8083
NOTIFICATION_SERVICE_PORT=8084
GATEWAY_PORT=8080
```

Save: `Ctrl+O` → `Enter` → `Ctrl+X`

**Verify permissions** (must show `-rw-------`):
```bash
ls -la /opt/revpay/.env
```

> [!TIP]
> Generate strong secrets right on the VM:
> ```bash
> openssl rand -base64 24   # for passwords
> openssl rand -hex 64       # for JWT secret
> ```

---

## STEP 9 — Log In to GHCR on the VM

GitHub Container Registry needs a Personal Access Token (PAT) to pull your images.

**Create the PAT** (do this on GitHub in your browser):
1. GitHub → **Settings** → **Developer settings** → **Personal access tokens** → **Tokens (classic)**
2. **Generate new token (classic)**
3. Note: `revpay-ec2-pull` | Expiry: `No expiration`
4. Scope: check only **`read:packages`**
5. Copy the token

**Login on the VM:**
```bash
echo "<YOUR_PAT_TOKEN>" | docker login ghcr.io -u <YOUR_GITHUB_USERNAME> --password-stdin
```

---

## STEP 10 — Copy docker-compose.yml and Start Infrastructure

```bash
# From your LOCAL machine — copy the compose file to the VM
scp -i ~/Downloads/revpay-ec2-key.pem \
  docker-compose.yml ubuntu@<YOUR_EC2_PUBLIC_IP>:/opt/revpay/

# Also copy the infra directory (nginx, prometheus, grafana configs)
scp -i ~/Downloads/revpay-ec2-key.pem -r \
  infra/ ubuntu@<YOUR_EC2_PUBLIC_IP>:/opt/revpay/
```

**SSH into VM and start infra:**
```bash
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
cd /opt/revpay

# Remove the obsolete 'version:' key from docker-compose.yml
sed -i '/^version:/d' docker-compose.yml

# Start infrastructure services only (NOT the microservices — CI/CD deploys those)
docker compose --env-file .env up -d \
  postgres redis zookeeper kafka kafka-ui nginx prometheus grafana zipkin

# Verify all are running
docker compose --env-file .env ps
```

---

<!--
========================================================================
BACKUP — ORACLE GITHUB SECRETS (COMMENTED OUT)
========================================================================
## STEP 11 — Add GitHub Secrets
Add these repository secrets: OCI_HOST, OCI_USER, OCI_SSH_KEY, etc.
========================================================================
-->

## STEP 11 — Add GitHub Secrets (AWS EC2)

Go to: **GitHub repo → Settings → Secrets and variables → Actions → New repository secret**

Add each one:

| Secret Name | Value |
|---|---|
| `EC2_HOST` | Your AWS EC2 Public IP (e.g. `54.x.x.x`) |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | **Full contents** of your `revpay-ec2-key.pem` file (include `-----BEGIN...-----` lines) |
| `POSTGRES_USER` | Same value as in `/opt/revpay/.env` |
| `POSTGRES_PASSWORD` | Same value as in `/opt/revpay/.env` |
| `REDIS_PASSWORD` | Same value as in `/opt/revpay/.env` |
| `JWT_SECRET` | Same value as in `/opt/revpay/.env` |

---

## STEP 12 — Create the `production` GitHub Environment

The deploy jobs use `environment: production`. You need to create it.

1. GitHub → **Settings** → **Environments** → **New environment**
2. Name: `production`
3. (Optional but recommended) **Required reviewers** → add yourself → **Save protection rules**

---

## STEP 13 — Trigger Your First Deploy

```bash
# Make a small change to user-service to trigger only that pipeline
cd <your local project>

# Add a blank line to trigger the path filter
echo "" >> user-service/src/main/resources/application.properties

git add user-service/src/main/resources/application.properties
git commit -m "ci: trigger first AWS EC2 deploy for user-service"
git push origin master
```

**Watch it run:**  
Go to `github.com/<your-username>/RevPay---Distributed-Payment-System/actions`

You should see `CI/CD — User Service` workflow running with 3 jobs:
- Job 1: Build & Test (Maven + Testcontainers)
- Job 2: Docker Build & Push (image pushed to GHCR)
- Job 3: Deploy to AWS EC2 VM (SSH → pull → run with limits → health check)

---

## STEP 14 — Verify Everything On the VM

```bash
ssh -i ~/Downloads/revpay-ec2-key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>

# 1. Container is running?
docker ps | grep user-service

# 2. Health check passes?
curl -s http://localhost:8081/actuator/health | python3 -m json.tool

# 3. Hit through Nginx (public access)?
curl http://<YOUR_EC2_PUBLIC_IP>/actuator/health
```

---

## Common Failure Points & Fixes

| Symptom | Fix |
|---|---|
| `Deploy` job: `SSH connection refused` | AWS Security Group port 22 not open — recheck Step 5 |
| `Deploy` job: `docker pull` fails | GHCR login expired on VM — re-run Step 9 |
| VM crashes or becomes unresponsive | Out of memory — verify Step 7 swap allocation succeeded (`swapon -s`) |
| Actuator health status is DOWN | Check logs (`docker logs user-service`) — usually a database/broker configuration or authentication issue |
