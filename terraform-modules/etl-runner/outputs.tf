# === Core instance information ===
output "instance_id" {
  description = "The ID of the EC2 instance"
  value       = module.etl_runner.id
}

output "instance_arn" {
  description = "The ARN of the EC2 instance"
  value       = module.etl_runner.arn
}

output "instance_state" {
  description = "Current state of the EC2 instance (e.g. running)"
  value       = module.etl_runner.instance_state
}

output "availability_zone" {
  description = "Availability zone the instance was launched in"
  value       = module.etl_runner.availability_zone
}

# === Networking ===
output "private_ip" {
  description = "Private IP address of the instance"
  value       = module.etl_runner.private_ip
}

output "private_dns" {
  description = "Private DNS name of the instance"
  value       = module.etl_runner.private_dns
}

output "primary_network_interface_id" {
  description = "ID of the primary network interface"
  value       = module.etl_runner.primary_network_interface_id
}

# === Artifact locations (what Jenkins polls and validates) ===
output "status_s3_uri" {
  description = "Completion sentinel. Present only once the run is over and all other artifacts are uploaded."
  value       = "s3://${var.stack_s3_bucket}/${local.reports_prefix}/status.json"
}

output "reports_s3_uri" {
  description = "Prefix the job's JSON reports and any generated CSVs are synced to."
  value       = "s3://${var.stack_s3_bucket}/${local.reports_prefix}/"
}

output "log_s3_uri" {
  description = "Full runner log (bootstrap + container stdout/stderr)."
  value       = "s3://${var.stack_s3_bucket}/etl-runner/logs/${var.module_name}-${var.run_id}.log"
}

output "run_id" {
  description = "Correlation id passed to the job as --run-id"
  value       = var.run_id
}
