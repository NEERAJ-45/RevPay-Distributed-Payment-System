resource "aws_db_subnet_group" "rds" {
  name       = "revpay-${var.environment}-rds-subnet-group"
  subnet_ids = var.subnet_ids

  tags = {
    Project     = "revpay"
    Environment = var.environment
  }
}

resource "aws_security_group" "rds" {
  name        = "revpay-${var.environment}-rds-sg"
  description = "Security group for RevPay RDS PostgreSQL"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from EC2"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.allowed_sg_id]
  }

  tags = {
    Name        = "revpay-${var.environment}-rds-sg"
    Project     = "revpay"
    Environment = var.environment
  }
}

resource "aws_db_parameter_group" "rds" {
  name   = "revpay-${var.environment}-pg"
  family = "postgres16"

  parameter {
    name  = "log_statement"
    value = "ddl"
  }
}

resource "aws_db_instance" "postgres" {
  identifier             = "revpay-${var.environment}-pg"
  engine                 = "postgres"
  engine_version         = "16.3"
  instance_class         = var.instance_class
  allocated_storage      = 20
  storage_type           = "gp3"
  storage_encrypted      = true

  db_name                = "upi_users"
  username               = "upi_admin"
  password               = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.rds.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:00-sun:05:00"

  skip_final_snapshot     = var.environment == "prod" ? false : true

  parameter_group_name    = aws_db_parameter_group.rds.name

  tags = {
    Project     = "revpay"
    Environment = var.environment
  }
}
