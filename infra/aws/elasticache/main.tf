resource "aws_elasticache_subnet_group" "redis" {
  name       = "revpay-${var.environment}-redis-subnet-group"
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "redis" {
  name        = "revpay-${var.environment}-redis-sg"
  description = "Security group for RevPay ElastiCache Redis"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from EC2"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.allowed_sg_id]
  }

  tags = {
    Name        = "revpay-${var.environment}-redis-sg"
    Project     = "revpay"
    Environment = var.environment
  }
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id          = "revpay-${var.environment}-redis"
  description                   = "RevPay Redis for idempotency and rate limiting"
  node_type                     = var.node_type
  num_cache_clusters            = 1
  port                          = 6379
  parameter_group_name          = "default.redis7"
  subnet_group_name             = aws_elasticache_subnet_group.redis.name
  security_group_ids            = [aws_security_group.redis.id]
  automatic_failover_enabled    = false
  multi_az_enabled              = false
  auth_token                    = var.redis_password
  transit_encryption_enabled    = true
  at_rest_encryption_enabled    = true

  tags = {
    Project     = "revpay"
    Environment = var.environment
  }
}
