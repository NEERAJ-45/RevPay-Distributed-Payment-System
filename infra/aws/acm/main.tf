resource "aws_acm_certificate" "cert" {
  count = var.domain_name != "" ? 1 : 0

  domain_name       = var.domain_name
  validation_method = "DNS"

  provider = aws.us_east_1

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project     = "revpay"
    Environment = var.environment
  }
}

resource "aws_route53_record" "validation" {
  count = var.domain_name != "" ? 1 : 0

  zone_id = data.aws_route53_zone.selected[0].zone_id
  name    = aws_acm_certificate.cert[0].domain_validation_options[0].resource_record_name
  type    = aws_acm_certificate.cert[0].domain_validation_options[0].resource_record_type
  records = [aws_acm_certificate.cert[0].domain_validation_options[0].resource_record_value]
  ttl     = 60
}

resource "aws_acm_certificate_validation" "cert" {
  count = var.domain_name != "" ? 1 : 0

  certificate_arn         = aws_acm_certificate.cert[0].arn
  validation_record_fqdns = aws_route53_record.validation[*].fqdn

  provider = aws.us_east_1
}

data "aws_route53_zone" "selected" {
  count = var.domain_name != "" ? 1 : 0

  name         = var.domain_name
  private_zone = false
}
