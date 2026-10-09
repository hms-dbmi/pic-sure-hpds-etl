#!/bin/bash
#
# Participant database bootstrap: install Postgres, restore the latest dump from S3 (or create
# a fresh schema), publish the connection secret, report ready.
#
# Rendered by Terraform's templatefile(). Every $${...} here is a Terraform placeholder, so bash
# variables are written without braces to keep the two syntaxes apart. A braced bash expansion
# must double the dollar sign to escape it from Terraform.
#
# Contract with participant-db-start:
#   - all output lands in /var/log/participant-db.log, uploaded to S3 on every exit path
#   - status.json is uploaded LAST: {"status": "ready"|"failed", "phase", "restoredFrom"}
#   - the secret gets its value only on success, so a reader never sees a half-built database
#   - unlike a runner, this instance does NOT shut itself down: on success it serves the
#     pipeline; on failure participant-db-start destroys it (nothing has been written yet,
#     and the restore source is still in S3)
#
# xtrace is NOT enabled: this script handles the database password.
set -euo pipefail

LOG=/var/log/participant-db.log
PGDATA=/var/lib/pgsql/data
WORK=/var/lib/pgsql/restore
STATUS_FILE=/tmp/participant-db-status.json

touch "$LOG"
exec > >(tee -a "$LOG") 2>&1

PHASE=boot
STATUS=failed
RESTORED_FROM=""

say() { echo "[$(date -u +%H:%M:%S)] [participant-db] $*"; }

imds() {
  local token
  token=$(curl -sf -m 5 -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null) || return 0
  curl -sf -m 5 -H "X-aws-ec2-metadata-token: $token" \
    "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null || true
}

publish_status() {
  set +e
  say "Boot finished: status=$STATUS phase=$PHASE restoredFrom=$${RESTORED_FROM:-<fresh schema>}"
  jq -n --arg status "$STATUS" --arg phase "$PHASE" --arg restored "$RESTORED_FROM" \
        --arg instance "$(imds instance-id)" --arg run "${run_id}" --arg env "${env_name}" \
        --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        '{status: $status, phase: $phase, restoredFrom: $restored, instanceId: $instance,
          runId: $run, env: $env, finishedAt: $at}' > "$STATUS_FILE"
  aws s3 cp "$LOG" "s3://${stack_s3_bucket}/${log_key}" --region "${aws_region}" --no-progress \
    || say "WARN: log upload failed"
  # Sentinel last: its presence means the log is already in S3.
  aws s3 cp "$STATUS_FILE" "s3://${stack_s3_bucket}/${status_key}" --region "${aws_region}" --no-progress \
    || say "WARN: status upload failed"
}
trap publish_status EXIT

# --------------------------------------------------------------------------
PHASE=install
# SRCE RHEL9 golden image, initialised the way the pheno ETL environment's hosts are
# (avillach-jenkins-bdc-etl). No containers here, so podman stays off.
say "SRCE golden image startup"
echo "ENABLE_PODMAN=false" > /opt/srce/startup.config
if [ -f /opt/srce/scripts/start-gsstools.sh ]; then
  sh /opt/srce/scripts/start-gsstools.sh
fi
dnf -y update

# RHEL9 ships PostgreSQL as module streams; the stream selects the major version.
say "Installing PostgreSQL ${pg_version}"
dnf module reset -y postgresql
dnf module enable -y "postgresql:${pg_version}"
dnf install -y postgresql postgresql-server jq tar
command -v aws >/dev/null 2>&1 || dnf install -y awscli
# SSM only -- no SSH key. participant-db-stop runs the backup through it.
systemctl enable --now amazon-ssm-agent

# The golden image's nftables firewall drops unlisted inbound ports; the security group still
# limits 5432 to the runner groups. Persisted the same way the pheno hosts persist 443.
nft add rule inet filter input tcp dport 5432 accept
nft list ruleset > /etc/nftables/nftables.rules
systemctl restart nftables

# --------------------------------------------------------------------------
PHASE=configure
postgresql-setup --initdb

MEM_MB=$(awk '/MemTotal/ {print int($2 / 1024)}' /proc/meminfo)
cat >> "$PGDATA/postgresql.conf" <<PGCONF

# --- participant-db ---
listen_addresses = '*'
max_connections = ${max_connections}
password_encryption = scram-sha-256
shared_buffers = $((MEM_MB / 4))MB
effective_cache_size = $((MEM_MB * 3 / 4))MB
maintenance_work_mem = 512MB
PGCONF

# The app role over TCP (localhost for the restore, the VPC for runners -- the security group
# further limits that to the runner groups); the postgres superuser over the local socket only.
cat > "$PGDATA/pg_hba.conf" <<'PGHBA'
local all postgres                peer
host  all ${db_username} 127.0.0.1/32 scram-sha-256
host  ${db_name} ${db_username} ${vpc_cidr} scram-sha-256
PGHBA
chown postgres:postgres "$PGDATA/pg_hba.conf"

systemctl enable --now postgresql
for _ in $(seq 1 30); do
  runuser -u postgres -- pg_isready -q && break
  sleep 2
done
runuser -u postgres -- pg_isready

# The password is generated here and leaves this host only through Secrets Manager. It is
# handed to psql through the environment (\getenv), never on a command line.
DB_PASSWORD=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
export DB_PASSWORD
runuser -u postgres -- psql -v ON_ERROR_STOP=1 -q <<'SQL'
\getenv pw DB_PASSWORD
CREATE ROLE "${db_username}" LOGIN PASSWORD :'pw';
CREATE DATABASE "${db_name}" OWNER "${db_username}";
SQL

app_psql() {
  PGPASSWORD="$DB_PASSWORD" psql --host=127.0.0.1 --port=5432 --username="${db_username}" \
    --dbname="${db_name}" -v ON_ERROR_STOP=1 "$@"
}

# --------------------------------------------------------------------------
PHASE=restore
BACKUPS="s3://${backup_s3_bucket}/${backup_key_prefix}"
RESTORE_FROM='${restore_from}'
DUMP_URI=""

case "$RESTORE_FROM" in
  "")
    LATEST=$(aws s3 cp "$BACKUPS/LATEST" - --region "${aws_region}" 2>/dev/null | tr -d '[:space:]' || true)
    if [[ -n "$LATEST" ]]; then
      DUMP_URI="$BACKUPS/$LATEST"
    else
      say "No $BACKUPS/LATEST -- starting from a fresh schema"
    fi
    ;;
  none) say "RESTORE_FROM=none -- starting from a fresh schema" ;;
  s3://*) DUMP_URI="$RESTORE_FROM" ;;
  *) DUMP_URI="$BACKUPS/$RESTORE_FROM" ;;
esac

if [[ -n "$DUMP_URI" ]]; then
  say "Restoring $DUMP_URI"
  rm -rf "$WORK"; mkdir -p "$WORK"
  aws s3 cp "$DUMP_URI" "$WORK/dump.tar" --region "${aws_region}" --no-progress
  tar -xf "$WORK/dump.tar" -C "$WORK"
  rm -f "$WORK/dump.tar"

  # A tarred pg_dump --format=d directory: whatever the directory is called, it holds toc.dat.
  TOC=$(find "$WORK" -name toc.dat -print -quit)
  if [[ -z "$TOC" ]]; then
    say "ERROR: $DUMP_URI holds no toc.dat -- expected a tar of a 'pg_dump --format=d' directory"
    exit 1
  fi

  PGPASSWORD="$DB_PASSWORD" pg_restore --host=127.0.0.1 --port=5432 --username="${db_username}" \
    --dbname="${db_name}" --format=d --jobs="$(nproc)" \
    --no-owner --no-privileges --exit-on-error --verbose "$(dirname "$TOC")"
  rm -rf "$WORK"

  if [[ "$(app_psql -tAc "SELECT count(*) FROM information_schema.schemata WHERE schema_name = '${db_schema}'")" != "1" ]]; then
    say "ERROR: the restored dump has no '${db_schema}' schema (was it taken with --schema ${db_schema}?)"
    exit 1
  fi
  RESTORED_FROM="$DUMP_URI"
else
  echo '${schema_sql_b64}' | base64 -d > /tmp/schema.sql
  app_psql -q <<SQL
CREATE SCHEMA "${db_schema}";
SET search_path TO "${db_schema}";
\i /tmp/schema.sql
SQL
  rm -f /tmp/schema.sql
fi

# Unqualified names resolve to the schema for anyone connecting as the app role (the JAR also
# sets it through Hikari's DB_SCHEMA).
app_psql -q -c "ALTER ROLE \"${db_username}\" SET search_path TO \"${db_schema}\""

say "Row counts: $(app_psql -tA -F ' ' -c "SELECT 'participants=' || (SELECT count(*) FROM ${db_schema}.participants),
       'consents='     || (SELECT count(*) FROM ${db_schema}.consents),
       'samples='      || (SELECT count(*) FROM ${db_schema}.samples)")"

# --------------------------------------------------------------------------
PHASE=backup-tooling
echo '${backup_script_b64}' | base64 -d > /usr/local/bin/participant-db-backup
chmod 700 /usr/local/bin/participant-db-backup

# --------------------------------------------------------------------------
PHASE=publish
HOST=$(imds local-ipv4)
SECRET_FILE=$(mktemp)
chmod 600 "$SECRET_FILE"
jq -n --arg host "$HOST" --arg db "${db_name}" --arg user "${db_username}" --arg pw "$DB_PASSWORD" \
      --arg schema "${db_schema}" \
      '{engine: "postgres", host: $host, port: 5432, dbname: $db, schema: $schema,
        username: $user, password: $pw}' > "$SECRET_FILE"
aws secretsmanager put-secret-value --secret-id "${db_secret_id}" --region "${aws_region}" \
  --secret-string "file://$SECRET_FILE" >/dev/null
shred -u "$SECRET_FILE" 2>/dev/null || rm -f "$SECRET_FILE"
unset DB_PASSWORD
say "Published connection details to ${db_secret_id} (host $HOST)"

PHASE=ready
STATUS=ready
