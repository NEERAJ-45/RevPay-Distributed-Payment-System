variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "node_type" {
  type = string
}

variable "redis_password" {
  type      = string
  sensitive = true
}

variable "allowed_sg_id" {
  type = string
}

variable "environment" {
  type = string
}
