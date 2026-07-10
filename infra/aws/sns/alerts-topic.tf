# ════════════════════════════════════════════════════════════════════
# RevPay — SNS Alerts Topic (Terraform Stub)
# ════════════════════════════════════════════════════════════════════
# Single SNS topic for all alarm notifications.
# Free Tier: 1,000 email notifications/month
# ════════════════════════════════════════════════════════════════════

# ── SNS Topic ─────────────────────────────────────────────────────

resource "aws_sns_topic" "revpay_alerts" {
  name = "revpay-alerts"

  tags = {
    Project     = "revpay"
    Purpose     = "alarm-notifications"
    Environment = var.environment
  }
}

# ── Email Subscription ────────────────────────────────────────────
# After applying, AWS sends a confirmation email. You MUST click the
# confirmation link or you won't receive any alarm notifications.

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.revpay_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# ── Outputs ───────────────────────────────────────────────────────

output "sns_topic_arn" {
  description = "ARN of the revpay-alerts SNS topic (pass to alarms.tf)"
  value       = aws_sns_topic.revpay_alerts.arn
}

# ── Future: Slack Integration (Optional) ──────────────────────────
#
# To send alarms to Slack instead of email:
#   1. Create a Slack webhook URL
#   2. Create a Lambda function that receives SNS and posts to Slack
#   3. Subscribe the Lambda to this SNS topic
#
# resource "aws_sns_topic_subscription" "slack_lambda" {
#   topic_arn = aws_sns_topic.revpay_alerts.arn
#   protocol  = "lambda"
#   endpoint  = aws_lambda_function.slack_notifier.arn
# }
