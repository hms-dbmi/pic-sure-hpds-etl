output "instance_id" {
  description = "EC2 instance id the monitor polls"
  value       = module.etl_runner.instance_id
}

output "status_s3_uri" {
  description = "Completion sentinel carrying the job's exit code"
  value       = module.etl_runner.status_s3_uri
}

output "reports_s3_uri" {
  description = "Prefix holding the JobResult JSON, the sstr sub-reports, and the per-study mapping CSVs"
  value       = module.etl_runner.reports_s3_uri
}

output "log_s3_uri" {
  description = "Full runner log"
  value       = module.etl_runner.log_s3_uri
}

output "run_id" {
  description = "Correlation id passed to the job"
  value       = module.etl_runner.run_id
}

output "availability_zone" {
  value       = module.etl_runner.availability_zone
  description = "AZ the runner launched in"
}

output "private_ip" {
  value       = module.etl_runner.private_ip
  description = "Private IP of the runner"
}
