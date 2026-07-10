output "certificate_arn" {
  value = var.domain_name != "" ? aws_acm_certificate.cert[0].arn : ""
}
