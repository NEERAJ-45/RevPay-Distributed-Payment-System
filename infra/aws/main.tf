data "aws_availability_zones" "available" {}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

module "vpc" {
  source = "./vpc"

  vpc_cidr             = var.vpc_cidr
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  availability_zones   = local.azs
  environment          = var.environment
}

module "iam" {
  source = "./iam"

  environment = var.environment
}

module "acm" {
  source = "./acm"

  domain_name  = var.domain_name
  environment  = var.environment
  providers = {
    aws.us_east_1 = aws.us_east_1
  }
}

module "ec2" {
  source = "./ec2"

  vpc_id             = module.vpc.vpc_id
  subnet_id          = module.vpc.public_subnet_ids[0]
  instance_type      = var.ec2_instance_type
  key_name           = var.ec2_key_name
  ssh_allowed_cidr   = var.ec2_ssh_allowed_cidr
  iam_instance_profile = module.iam.ec2_instance_profile_name
  environment        = var.environment
  rds_host           = module.rds.endpoint
  rds_user           = module.rds.username
  rds_password       = var.rds_password
  redis_host         = module.elasticache.redis_endpoint
  redis_password     = var.redis_password
  kafka_bootstrap    = "localhost:9092"
  jwt_secret         = var.jwt_secret

  depends_on = [module.rds, module.elasticache]
}

module "alb" {
  source = "./alb"

  vpc_id             = module.vpc.vpc_id
  public_subnet_ids  = module.vpc.public_subnet_ids
  ec2_instance_ids   = module.ec2.instance_ids
  certificate_arn    = module.acm.certificate_arn
  environment        = var.environment
}

module "rds" {
  source = "./rds"

  vpc_id          = module.vpc.vpc_id
  subnet_ids      = module.vpc.private_subnet_ids
  instance_class  = var.rds_instance_class
  db_password     = var.rds_password
  allowed_sg_id   = module.ec2.security_group_id
  environment     = var.environment
}

module "elasticache" {
  source = "./elasticache"

  vpc_id          = module.vpc.vpc_id
  subnet_ids      = module.vpc.private_subnet_ids
  node_type       = var.redis_node_type
  redis_password  = var.redis_password
  allowed_sg_id   = module.ec2.security_group_id
  environment     = var.environment
}

module "cloudwatch" {
  source = "./cloudwatch"

  environment  = var.environment
  sns_topic_arn = var.alert_email != "" ? module.sns.topic_arn : ""
  alb_arn_suffix = module.alb.alb_arn_suffix
}

module "sns" {
  source = "./sns"

  alert_email = var.alert_email
  environment = var.environment
}
