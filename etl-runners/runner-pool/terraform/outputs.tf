output "instance_ids" {
  description = "Worker instance ids, one per worker, in worker order"
  value       = [for k in sort(tolist(local.workers)) : module.worker[k].instance_id]
}

output "mailbox_s3_prefix" {
  description = "Mailboxes root: a worker reads <this>/<instance-id>/task.json"
  value       = "s3://${var.stack_s3_bucket}/${local.pool_prefix}"
}

output "worker_log_s3_prefix" {
  description = "Each worker's own log lands at <this>-<instance-id>.log when it terminates"
  value       = "s3://${var.stack_s3_bucket}/etl-runner/logs/${var.pool_job}-${var.run_id}"
}
