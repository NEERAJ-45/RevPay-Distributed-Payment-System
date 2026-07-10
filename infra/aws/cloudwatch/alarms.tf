# ════════════════════════════════════════════════════════════════════
# RevPay — CloudWatch Alarms (Terraform Stub)
# ════════════════════════════════════════════════════════════════════
# Free Tier: 10 alarms max
# All alarms → single SNS topic → email notification
# ════════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════
# SEV1 — CRITICAL (drop everything)
# ═══════════════════════════════════════════════════════════════════

# Alarm #1: Payment failures spiking
resource "aws_cloudwatch_metric_alarm" "payment_failures_critical" {
  alarm_name          = "revpay-payment-failures-critical"
  alarm_description   = "SEV1: Payment failure count > 10 in 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "PaymentFailureCount"
  namespace           = "RevPay/Transactions"
  period              = 300       # 5 minutes
  statistic           = "Sum"
  threshold           = 10
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]
  ok_actions          = [var.sns_topic_arn]

  tags = { Severity = "SEV1", Service = "transaction-service" }
}

# Alarm #2: System-wide 5xx errors
resource "aws_cloudwatch_metric_alarm" "http_5xx_critical" {
  alarm_name          = "revpay-5xx-errors-critical"
  alarm_description   = "SEV1: HTTP 5xx errors > 20 in 5 minutes across all services"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "HttpErrors5xx"
  namespace           = "RevPay/System"
  period              = 300
  statistic           = "Sum"
  threshold           = 20
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]
  ok_actions          = [var.sns_topic_arn]

  tags = { Severity = "SEV1", Service = "all" }
}

# Alarm #7: ALB unhealthy targets (any service down)
resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_targets" {
  alarm_name          = "revpay-alb-unhealthy"
  alarm_description   = "SEV1: One or more ALB targets are unhealthy"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "UnHealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 120       # 2 minutes
  statistic           = "Maximum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]
  ok_actions          = [var.sns_topic_arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = var.alb_arn_suffix != "" ? var.alb_arn_suffix : ""
  }

  tags = { Severity = "SEV1", Service = "alb" }
}

# Alarm #8: ALB 5xx responses
resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "revpay-alb-5xx"
  alarm_description   = "SEV1: ALB returning > 20 5xx responses in 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "HTTPCode_ELB_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = 300
  statistic           = "Sum"
  threshold           = 20
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]
  ok_actions          = [var.sns_topic_arn]

  tags = { Severity = "SEV1", Service = "alb" }
}

# ═══════════════════════════════════════════════════════════════════
# SEV2 — HIGH (investigate within 1 hour)
# ═══════════════════════════════════════════════════════════════════

# Alarm #3: Payment latency too high
resource "aws_cloudwatch_metric_alarm" "payment_latency_high" {
  alarm_name          = "revpay-latency-high"
  alarm_description   = "SEV2: Payment P99 latency > 2000ms for 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "PaymentLatencyP99"
  namespace           = "RevPay/Transactions"
  period              = 300
  statistic           = "p99"     # CloudWatch extended statistic
  threshold           = 2000      # milliseconds
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]

  tags = { Severity = "SEV2", Service = "transaction-service" }
}

# Alarm #4: Sagas stuck in PENDING state
resource "aws_cloudwatch_metric_alarm" "saga_stuck" {
  alarm_name          = "revpay-saga-stuck"
  alarm_description   = "SEV2: More than 50 sagas in PENDING state for 10 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2         # 2 × 5min = 10 minutes sustained
  metric_name         = "SagaPendingCount"
  namespace           = "RevPay/Transactions"
  period              = 300
  statistic           = "Maximum"
  threshold           = 50
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]

  tags = { Severity = "SEV2", Service = "transaction-service" }
}

# Alarm #5: Outbox events backlogging
resource "aws_cloudwatch_metric_alarm" "outbox_backlog" {
  alarm_name          = "revpay-outbox-backlog"
  alarm_description   = "SEV2: Outbox queue depth > 100 for 10 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "OutboxPendingCount"
  namespace           = "RevPay/UserService"
  period              = 300
  statistic           = "Maximum"
  threshold           = 100
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]

  tags = { Severity = "SEV2", Service = "user-service" }
}

# Alarm #6: Rate limiter rejecting too many requests
resource "aws_cloudwatch_metric_alarm" "rate_limit_surge" {
  alarm_name          = "revpay-rate-limit-surge"
  alarm_description   = "SEV2: Rate limiter rejecting > 100 requests in 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "RateLimitRejections"
  namespace           = "RevPay/Gateway"
  period              = 300
  statistic           = "Sum"
  threshold           = 100
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]

  tags = { Severity = "SEV2", Service = "api-gateway" }
}

# ═══════════════════════════════════════════════════════════════════
# SEV3 — WARNING (investigate within 1 day)
# ═══════════════════════════════════════════════════════════════════

# Alarm #9: Idempotency cache hit surge (duplicate requests)
resource "aws_cloudwatch_metric_alarm" "idempotency_surge" {
  alarm_name          = "revpay-idempotency-surge"
  alarm_description   = "SEV3: Idempotency cache hits > 500 in 5 min (duplicate traffic)"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "IdempotencyCacheHits"
  namespace           = "RevPay/Transactions"
  period              = 300
  statistic           = "Sum"
  threshold           = 500
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]

  tags = { Severity = "SEV3", Service = "transaction-service" }
}

# ═══════════════════════════════════════════════════════════════════
# BILLING — Most Important Alarm (set up FIRST)
# ═══════════════════════════════════════════════════════════════════

# Alarm #10: AWS billing exceeds $1
# NOTE: Billing metrics are ONLY available in us-east-1 region
resource "aws_cloudwatch_metric_alarm" "billing_alarm" {
  provider            = aws.us_east_1   # Billing metrics only in us-east-1
  alarm_name          = "revpay-billing-alert"
  alarm_description   = "BILLING: AWS estimated charges exceed $1.00"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "EstimatedCharges"
  namespace           = "AWS/Billing"
  period              = 21600     # 6 hours
  statistic           = "Maximum"
  threshold           = 1.0       # $1 USD
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.sns_topic_arn]

  dimensions = {
    Currency = "USD"
  }

  tags = { Severity = "BILLING", Service = "aws-account" }
}

# ═══════════════════════════════════════════════════════════════════
# Summary: 10/10 alarms used (Free Tier exhausted)
# ═══════════════════════════════════════════════════════════════════
#
# SEV1 (4): payment-failures, 5xx-errors, alb-unhealthy, alb-5xx
# SEV2 (4): latency-high, saga-stuck, outbox-backlog, rate-limit
# SEV3 (1): idempotency-surge
# BILL (1): billing-alert
#
# All alarms → SNS topic "revpay-alerts" → your email
