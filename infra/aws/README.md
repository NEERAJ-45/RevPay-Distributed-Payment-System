# RevPay AWS Infrastructure

## Architecture

```
Internet → ALB (port 80/443) → EC2 (port 8080: api-gateway)
                                EC2 (Docker Compose: 5 services)
                                     ↓
                    ┌─────────────────┼─────────────────┐
                    ↓                 ↓                  ↓
              RDS PostgreSQL    ElastiCache Redis     Kafka (on EC2)
```

## Modules

| Module | Resources | Cost (Free Tier) |
|--------|-----------|-------------------|
| `vpc/` | VPC, subnets, IGW, NAT, route tables | Free |
| `iam/` | IAM role + instance profile for EC2 | Free |
| `ec2/` | EC2 instance (t2.micro) + SG + user-data | 750 hrs/month |
| `rds/` | PostgreSQL 16 (db.t4g.micro) | 750 hrs/month |
| `elasticache/` | Redis 7 (cache.t4g.micro) | 750 hrs/month |
| `alb/` | ALB, target group, listeners | 750 hrs + 15 LCUs |
| `acm/` | SSL certificate (if domain provided) | Free |
| `cloudwatch/` | Log groups, alarms (10), dashboards | 5 GB logs + 10 alarms |
| `sns/` | Email subscription for alarms | 1000 emails/month |

## Prerequisites

1. **AWS CLI** configured with credentials
2. **SSH key pair** created in AWS (for EC2 access)
3. **S3 bucket** for Terraform state (recommended)

## Quick Start

```bash
# 1. Copy and edit variables
cp infra/aws/terraform.tfvars.example infra/aws/terraform.tfvars

# 2. Initialize Terraform
cd infra/aws
terraform init

# 3. Review the plan
terraform plan

# 4. Apply
terraform apply -auto-approve

# 5. Get the ALB DNS name
terraform output alb_dns_name
```

## Deployment Steps (after infra is up)

```bash
# SSH into EC2
ssh -i your-key.pem ec2-user@$(terraform output -raw ec2_public_ip)

# Copy application files
scp -i your-key.pem docker-compose.yml ec2-user@host:/opt/revpay/

# Start services
cd /opt/revpay
docker-compose up -d
```

## Important Notes

- **NAT Gateway costs ~$32/month** — consider removing for dev (`count = 0`)
- **RDS + ElastiCache together exceed free tier** — use local Docker Postgres/Redis for dev
- **Kafka runs on EC2** via docker-compose (not MSK which costs ~$200+/month)
- **Change `JWT_SECRET`** — the default is insecure
