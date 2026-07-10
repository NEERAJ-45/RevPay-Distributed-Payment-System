variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "instance_type" {
  type = string
}

variable "key_name" {
  type = string
}

variable "ssh_allowed_cidr" {
  type = string
}

variable "iam_instance_profile" {
  type = string
}

variable "environment" {
  type = string
}

variable "rds_host" {
  type = string
}

variable "rds_user" {
  type = string
}

variable "rds_password" {
  type      = string
  sensitive = true
}

variable "redis_host" {
  type = string
}

variable "redis_password" {
  type      = string
  sensitive = true
}

variable "kafka_bootstrap" {
  type = string
}

variable "jwt_secret" {
  type      = string
  sensitive = true
}
