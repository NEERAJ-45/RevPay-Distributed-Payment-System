# ════════════════════════════════════════════════════════════════════
# RevPay — Application Load Balancer (Terraform Stub)
# ════════════════════════════════════════════════════════════════════
# Deployment: Docker containers on EC2
# Replaces: Nginx reverse proxy (infra/nginx/nginx.conf)
# Free Tier: 750 hours + 15 LCUs/month (12 months from account creation)
# ════════════════════════════════════════════════════════════════════

# ── ALB ───────────────────────────────────────────────────────────

resource "aws_lb" "revpay" {
  name               = "revpay-alb"
  internal           = false           # Public-facing
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  enable_deletion_protection = false   # Dev/learning — allow easy cleanup

  tags = {
    Project     = "revpay"
    Environment = var.environment
    Replaces    = "nginx"
  }
}

# ── Target Group: API Gateway ─────────────────────────────────────
# All traffic goes to API Gateway (port 8080)
# Gateway handles JWT, rate limiting, and routes to downstream services

resource "aws_lb_target_group" "api_gateway" {
  name     = "revpay-api-gateway-tg"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = var.vpc_id

  # Health check — uses Spring Boot Actuator endpoint
  health_check {
    enabled             = true
    path                = "/actuator/health"
    protocol            = "HTTP"
    port                = "8080"
    interval            = 30       # Check every 30s (free tier friendly)
    timeout             = 5        # 5s timeout (Spring Boot can be slow on cold start)
    healthy_threshold   = 3        # Must pass 3 checks to be "healthy"
    unhealthy_threshold = 2        # 2 failures = mark "unhealthy"
    matcher             = "200"    # Expect HTTP 200 from /actuator/health
  }

  # Deregistration delay: let in-flight requests finish before removing target
  deregistration_delay = 30

  tags = {
    Project = "revpay"
    Service = "api-gateway"
  }
}

# ── Target Registration ───────────────────────────────────────────
# Register EC2 instances running the API Gateway Docker container

resource "aws_lb_target_group_attachment" "gateway_instance" {
  count            = length(var.ec2_instance_ids)
  target_group_arn = aws_lb_target_group.api_gateway.arn
  target_id        = var.ec2_instance_ids[count.index]
  port             = 8080
}

# ── HTTPS Listener (port 443) ─────────────────────────────────────
# SSL termination happens here. ACM certificate is free.
# Traffic from ALB → EC2 is plain HTTP (inside VPC = secure).

resource "aws_lb_listener" "https" {
  count             = var.certificate_arn != "" ? 1 : 0
  load_balancer_arn = aws_lb.revpay.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api_gateway.arn
  }
}

# ── HTTP Listener (port 80) → Redirect to HTTPS ──────────────────

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.revpay.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# ── For Development: HTTP-only listener (no SSL) ──────────────────
# Use this while learning, before setting up ACM certificate

resource "aws_lb_listener" "http_dev" {
  load_balancer_arn = aws_lb.revpay.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api_gateway.arn
  }
}

# ── Outputs ───────────────────────────────────────────────────────

output "alb_dns_name" {
  description = "Public DNS name of the ALB (use this instead of localhost)"
  value       = aws_lb.revpay.dns_name
}

output "alb_arn" {
  description = "ARN of the ALB (needed for alarm dimensions)"
  value       = aws_lb.revpay.arn
}

output "target_group_arn" {
  description = "ARN of the API Gateway target group"
  value       = aws_lb_target_group.api_gateway.arn
}

# ════════════════════════════════════════════════════════════════════
# What the ALB replaces from nginx.conf:
#
# Nginx:                          →  ALB Equivalent:
# ─────────────────────────────────────────────────────────────────
# upstream api_gateway {          →  Target Group: revpay-api-gateway-tg
#   least_conn;                   →  Routing algorithm: "least_outstanding_requests"
#   server host.docker.internal   →  Target: EC2 instance running gateway container
# }
#
# location / {                    →  Listener rule: default action → forward to TG
#   proxy_pass http://api_gateway →  (automatic)
#   proxy_set_header X-Real-IP    →  ALB auto-adds X-Forwarded-For
#   limit_req zone=upi_limit      →  Stays in Spring Cloud Gateway (not ALB's job)
# }
#
# location /health {              →  Health check: /actuator/health
#   return 200 "Nginx OK"
# }
#
# location /nginx_status {        →  Gone. ALB publishes its own metrics to
#   stub_status on;                   CloudWatch under AWS/ApplicationELB namespace
# }
# ════════════════════════════════════════════════════════════════════
