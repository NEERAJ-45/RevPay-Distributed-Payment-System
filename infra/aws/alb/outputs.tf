output "alb_dns_name" {
  description = "Public DNS name of the ALB"
  value       = aws_lb.revpay.dns_name
}

output "alb_arn" {
  description = "ARN of the ALB"
  value       = aws_lb.revpay.arn
}

output "alb_arn_suffix" {
  description = "ARN suffix for CloudWatch dimensions"
  value       = aws_lb.revpay.arn_suffix
}

output "target_group_arn" {
  description = "ARN of the API Gateway target group"
  value       = aws_lb_target_group.api_gateway.arn
}

output "target_group_arn_suffix" {
  description = "ARN suffix for CloudWatch dimensions"
  value       = aws_lb_target_group.api_gateway.arn_suffix
}
