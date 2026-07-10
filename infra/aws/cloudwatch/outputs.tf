output "log_group_names" {
  description = "Map of service names to log group names"
  value = {
    api_gateway          = aws_cloudwatch_log_group.api_gateway.name
    user_service         = aws_cloudwatch_log_group.user_service.name
    wallet_service       = aws_cloudwatch_log_group.wallet_service.name
    transaction_service  = aws_cloudwatch_log_group.transaction_service.name
    notification_service = aws_cloudwatch_log_group.notification_service.name
    ec2_host             = aws_cloudwatch_log_group.ec2_host.name
  }
}
