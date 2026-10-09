output "project_name" {
  value       = aws_codebuild_project.integration_tests.name
  description = "Pass to run-codebuild.sh as CODEBUILD_PROJECT if not the default"
}

output "log_group_name" {
  value       = aws_cloudwatch_log_group.build.name
  description = "CloudWatch log group every build writes to"
}

output "service_role_arn" {
  value       = aws_iam_role.codebuild.arn
  description = "Role the builds run as"
}
