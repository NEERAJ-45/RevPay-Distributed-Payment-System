variable "alert_email" {
  description = "Email address to receive alarm notifications"
  type        = string
  default     = ""
}

variable "environment" {
  description = "Environment name"
  type        = string
}
