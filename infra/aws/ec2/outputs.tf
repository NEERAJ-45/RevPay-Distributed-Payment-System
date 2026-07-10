output "instance_ids" {
  value = [aws_instance.revpay.id]
}

output "public_ip" {
  value = aws_instance.revpay.public_ip
}

output "private_ip" {
  value = aws_instance.revpay.private_ip
}

output "security_group_id" {
  value = aws_security_group.ec2.id
}
