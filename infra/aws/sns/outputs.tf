output "topic_arn" {
  description = "ARN of the revpay-alerts SNS topic"
  value       = aws_sns_topic.revpay_alerts.arn
}
