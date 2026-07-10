data "aws_ami" "amazon_linux" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-kernel-6.1-x86_64"]
  }
}

resource "aws_security_group" "ec2" {
  name        = "revpay-${var.environment}-ec2-sg"
  description = "Security group for RevPay EC2 instance"
  vpc_id      = var.vpc_id

  ingress {
    description = "API Gateway from ALB"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "SSH access"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_allowed_cidr]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "revpay-${var.environment}-ec2-sg"
    Project     = "revpay"
    Environment = var.environment
  }
}

resource "aws_instance" "revpay" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  key_name               = var.key_name
  iam_instance_profile   = var.iam_instance_profile

  user_data = templatefile("${path.module}/user-data.sh", {
    rds_host        = var.rds_host
    rds_user        = var.rds_user
    rds_password    = var.rds_password
    redis_host      = var.redis_host
    redis_password  = var.redis_password
    kafka_bootstrap = var.kafka_bootstrap
    jwt_secret      = var.jwt_secret
    environment     = var.environment
  })

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name        = "revpay-${var.environment}-instance"
    Project     = "revpay"
    Environment = var.environment
  }
}
