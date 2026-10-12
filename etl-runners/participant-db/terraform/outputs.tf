output "instance_id" {
  description = "DB instance id: what participant-db-stop sends the backup command to"
  value       = aws_instance.db.id
}

output "private_ip" {
  description = "Address the runners connect to (also in the credentials file)"
  value       = aws_instance.db.private_ip
}

output "status_s3_uri" {
  description = "Boot sentinel: {status: ready|failed, phase, restoredFrom}"
  value       = "s3://${var.stack_s3_bucket}/${local.status_key}"
}

output "log_s3_uri" {
  description = "Boot log (install, restore, publish)"
  value       = "s3://${var.stack_s3_bucket}/${local.log_key}"
}

output "backup_s3_uri" {
  description = "Prefix holding participant_db.tar (the restore point) and participant_db_not_promoted.tar"
  value       = "s3://${var.backup_s3_bucket}/${local.backup_key_prefix}/"
}
