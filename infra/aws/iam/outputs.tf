output "ec2_role_name" {
  value = aws_iam_role.ec2.name
}

output "ec2_instance_profile_name" {
  value = aws_iam_instance_profile.ec2.name
}
