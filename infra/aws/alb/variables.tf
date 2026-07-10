variable "vpc_id" {
  description = "VPC ID where ALB and EC2 instances live"
  type        = string
}

variable "public_subnet_ids" {
  description = "List of public subnet IDs (at least 2 AZs required for ALB)"
  type        = list(string)
}

variable "ec2_instance_ids" {
  description = "List of EC2 instance IDs running the API Gateway containers"
  type        = list(string)
  default     = []
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS"
  type        = string
  default     = ""
}

variable "environment" {
  description = "Environment name"
  type        = string
}
