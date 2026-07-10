#!/bin/bash
set -ex

# ── Install Docker & Compose ──
dnf update -y
dnf install -y docker curl
systemctl enable docker
systemctl start docker
curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose

# ── Install CloudWatch Agent ──
dnf install -y amazon-cloudwatch-agent
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json <<'CWAGENT'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/revpay/*.log",
            "log_group_name": "/revpay/${environment}/ec2-host",
            "log_stream_name": "{instance_id}",
            "timezone": "UTC"
          }
        ]
      }
    }
  }
}
CWAGENT
systemctl enable amazon-cloudwatch-agent
systemctl start amazon-cloudwatch-agent

# ── Create app directory ──
mkdir -p /opt/revpay
cd /opt/revpay

# ── Environment file ──
cat > .env <<EOF
POSTGRES_USER=${rds_user}
POSTGRES_PASSWORD=${rds_password}
POSTGRES_HOST=${rds_host}
POSTGRES_PORT=5432
REDIS_HOST=${redis_host}
REDIS_PORT=6379
REDIS_PASSWORD=${redis_password}
KAFKA_BOOTSTRAP_SERVERS=${kafka_bootstrap}
JWT_SECRET=${jwt_secret}
JWT_EXPIRY_MS=86400000
GATEWAY_PORT=8080
USER_SERVICE_PORT=8081
WALLET_SERVICE_PORT=8082
TRANSACTION_SERVICE_PORT=8083
NOTIFICATION_SERVICE_PORT=8084
EOF

# ── docker-compose.yml will be copied from deployment pipeline ──
# For manual setup: scp docker-compose.yml ec2-user@host:/opt/revpay/
# Then: docker-compose up -d

echo "User-data complete. Ready for docker-compose deployment."
