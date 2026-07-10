# ════════════════════════════════════════════════════════════════════
# RevPay — CloudWatch Log Groups (Terraform Stub)
# ════════════════════════════════════════════════════════════════════
# Deployment: Docker containers on EC2
# Logs shipped via: CloudWatch Agent installed on EC2 host
# ════════════════════════════════════════════════════════════════════

# ── Log Groups ────────────────────────────────────────────────────

# One log group per microservice.
# Log stream naming: {ec2-instance-id}/{container-name}
# Example: i-0abc123def/api-gateway

resource "aws_cloudwatch_log_group" "api_gateway" {
  name              = "/revpay/${var.environment}/api-gateway"
  retention_in_days = 7   # Low-value gateway logs, keep short

  tags = {
    Project     = "revpay"
    Service     = "api-gateway"
    Environment = var.environment
  }
}

resource "aws_cloudwatch_log_group" "user_service" {
  name              = "/revpay/${var.environment}/user-service"
  retention_in_days = 7

  tags = {
    Project     = "revpay"
    Service     = "user-service"
    Environment = var.environment
  }
}

resource "aws_cloudwatch_log_group" "wallet_service" {
  name              = "/revpay/${var.environment}/wallet-service"
  retention_in_days = 7

  tags = {
    Project     = "revpay"
    Service     = "wallet-service"
    Environment = var.environment
  }
}

resource "aws_cloudwatch_log_group" "transaction_service" {
  name              = "/revpay/${var.environment}/transaction-service"
  retention_in_days = 14   # Payment audit trail — keep longer

  tags = {
    Project     = "revpay"
    Service     = "transaction-service"
    Environment = var.environment
  }
}

resource "aws_cloudwatch_log_group" "notification_service" {
  name              = "/revpay/${var.environment}/notification-service"
  retention_in_days = 3   # High volume, low value — shortest retention

  tags = {
    Project     = "revpay"
    Service     = "notification-service"
    Environment = var.environment
  }
}

# ── Infrastructure Logs ───────────────────────────────────────────

resource "aws_cloudwatch_log_group" "ec2_host" {
  name              = "/revpay/${var.environment}/ec2-host"
  retention_in_days = 3   # System-level logs (syslog, docker daemon)

  tags = {
    Project     = "revpay"
    Service     = "infrastructure"
    Environment = var.environment
  }
}

# ── Free Tier Budget Notes ────────────────────────────────────────
#
# Free Tier: 5 GB log ingestion + 5 GB storage per month
#
# Estimated usage with these retention periods:
#   - 5 services × ~100 MB/day = 500 MB/day ingestion
#   - 500 MB × 30 days = ~15 GB/month ingestion (OVER FREE TIER)
#
# To stay within free tier:
#   - Keep log levels at WARN+ in production (not DEBUG)
#   - Use sampling for high-volume endpoints
#   - Transaction-service stays at INFO (audit requirement)
#   - All others at WARN unless actively debugging
#
# IMPORTANT: Set up a CloudWatch billing alarm BEFORE enabling log shipping to avoid unexpected costs
