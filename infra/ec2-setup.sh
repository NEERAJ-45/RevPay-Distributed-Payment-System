#!/bin/bash
# ════════════════════════════════════════════════════════════════════
#  infra/ec2-setup.sh
#  One-time bootstrap script for AWS EC2 Free Tier VM (Ubuntu 22.04 LTS).
#  Run manually over SSH after provisioning the EC2 instance.
#
#  Usage:
#    ssh -i ~/.ssh/ec2_key.pem ubuntu@<EC2_PUBLIC_IP> 'bash -s' < infra/ec2-setup.sh
# ════════════════════════════════════════════════════════════════════

set -euo pipefail

echo "▶ Detected Ubuntu / Debian (apt-get) for AWS EC2 instance"

# ── 1. System update ─────────────────────────────────────────────────
echo "▶ Updating system packages..."
sudo apt-get update -y
sudo apt-get upgrade -y

# ── 2. Configure Swap Space (CRITICAL for 1GB RAM t2.micro/t3.micro) ─
echo "▶ Setting up 4 GB Swap space to prevent Out Of Memory crashes..."
if [ -f /swapfile ]; then
  echo "ℹ  Swapfile already exists. Skipping allocation."
else
  sudo fallocate -l 4G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=4096
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  echo "✅ 4 GB Swap Space successfully enabled!"
fi

# ── 3. Install Docker & Docker Compose ────────────────────────────────
echo "▶ Installing Docker..."
sudo apt-get install -y ca-certificates curl gnupg lsb-release

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update -y
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Enable and start Docker
sudo systemctl enable --now docker

# Add current user to docker group (avoids needing sudo for docker commands)
CURRENT_USER=$(whoami)
sudo usermod -aG docker "$CURRENT_USER"
echo "✅ Docker installed — you may need to re-login for group change to take effect"

# ── 4. Open firewall ports (OS-level UFW) ─────────────────────────────
echo "▶ Opening firewall ports (OS-level UFW)..."
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 8080/tcp   # api-gateway
sudo ufw allow 8081/tcp   # user-service
sudo ufw allow 8082/tcp   # wallet-service
sudo ufw allow 8083/tcp   # transaction-service
sudo ufw allow 8084/tcp   # notification-service
sudo ufw --force enable
echo "✅ UFW firewall active and rules applied."
echo "⚠  REMINDER: You must also open these inbound TCP ports (22, 80, 443, 8080-8084)"
echo "   in your AWS EC2 Console under Security Groups → Edit Inbound Rules."

# ── 5. Create app directory with locked permissions ──────────────────
echo "▶ Creating /opt/revpay directory..."
sudo mkdir -p /opt/revpay
sudo chown "$CURRENT_USER:$CURRENT_USER" /opt/revpay
chmod 700 /opt/revpay

# ── 6. Create .env file template ────────────────────────────────────
echo "▶ Creating /opt/revpay/.env template..."
cat > /opt/revpay/.env << 'EOF'
# ── Database ─────────────────────────────────────────────────────────
POSTGRES_USER=REPLACE_ME
POSTGRES_PASSWORD=REPLACE_ME
POSTGRES_HOST=postgres
POSTGRES_PORT=5432

# ── Redis ─────────────────────────────────────────────────────────────
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=REPLACE_ME

# ── Kafka ─────────────────────────────────────────────────────────────
KAFKA_BOOTSTRAP_SERVERS=kafka:29092

# ── JWT ───────────────────────────────────────────────────────────────
JWT_SECRET=REPLACE_ME
JWT_EXPIRY_MS=86400000

# ── Service Ports ─────────────────────────────────────────────────────
USER_SERVICE_PORT=8081
WALLET_SERVICE_PORT=8082
TRANSACTION_SERVICE_PORT=8083
NOTIFICATION_SERVICE_PORT=8084
GATEWAY_PORT=8080
EOF

# Lock .env to owner-only read/write
chmod 600 /opt/revpay/.env
echo "✅ /opt/revpay/.env created — fill in the REPLACE_ME values before running compose."

# ── 7. Create the shared Docker network ─────────────────────────────
echo "▶ Creating Docker network 'upi-net'..."
newgrp docker <<DOCKERGRP
docker network create upi-net 2>/dev/null && echo "✅ upi-net created" || echo "ℹ  upi-net already exists"
DOCKERGRP

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  AWS EC2 Bootstrap Complete! Next Steps:"
echo ""
echo "  1. Fill in /opt/revpay/.env with real secrets:"
echo "       nano /opt/revpay/.env"
echo ""
echo "  2. Log in to GHCR to pull packages:"
echo "       docker login ghcr.io -u <your-github-username>"
echo ""
echo "  3. Copy docker-compose.yml to the VM and start infra:"
echo "       scp -i <key.pem> docker-compose.yml ubuntu@<EC2_IP>:/opt/revpay/"
echo "       docker compose --env-file /opt/revpay/.env -f /opt/revpay/docker-compose.yml up -d \\"
echo "         postgres redis kafka zookeeper nginx prometheus grafana zipkin"
echo ""
echo "  4. Configure GitHub Secrets (Settings → Secrets → Actions):"
echo "       EC2_HOST    = <Public IP from EC2 Console>"
echo "       EC2_USER    = ubuntu"
echo "       EC2_SSH_KEY = <paste content of your .pem private key file>"
# Add databases secrets also
echo "       POSTGRES_USER, POSTGRES_PASSWORD, REDIS_PASSWORD, JWT_SECRET"
echo "════════════════════════════════════════════════════════════"
