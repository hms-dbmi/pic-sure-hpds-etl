#!/bin/bash
#
# participant-db-backup <promote: true|false>
#
# Dumps the participant schema with pg_dump (directory format, parallel), tars it, uploads it
# to the backup prefix, and verifies the upload. With promote=true it then points LATEST at
# the new dump, so the next participant-db-start restores it; with promote=false the dump is
# kept (for inspection or a manual RESTORE_FROM) but the next start still restores the
# previous LATEST. Installed on the DB instance at boot; run by participant-db-stop over SSM.
#
# Rendered by Terraform's templatefile(): $${...} is a Terraform placeholder; bash variables
# are written without braces.
#
# Last line of output on success:  BACKUP_RESULT file=<name>.tar bytes=<n> promoted=<bool>
set -euo pipefail

PROMOTE="$${1:-false}"
case "$PROMOTE" in true|false) ;; *) echo "usage: participant-db-backup true|false" >&2; exit 2 ;; esac

BUCKET="${backup_s3_bucket}"
PREFIX="${backup_key_prefix}"
REGION="${aws_region}"
WORK=/var/lib/pgsql/backup

TS=$(date -u '+%Y-%m-%dT%H%M%SZ')
NAME="participant_db_$TS"

rm -rf "$WORK"
mkdir -p "$WORK"
chown postgres:postgres "$WORK"

echo "[backup] pg_dump ${db_name} (schema ${db_schema}) -> $WORK/$NAME"
# As the postgres superuser over the local socket (peer auth), so no password is needed here.
# Flags follow the team's standard dump: object definitions and data only, nothing tied to
# this server (owners, grants, tablespaces, publications, ...), so it restores cleanly into a
# fresh server as any role.
runuser -u postgres -- pg_dump \
   --host=/run/postgresql \
   --port=5432 \
   --file="$WORK/$NAME" \
   --format=d \
   --jobs=10 \
   --verbose \
   --no-owner --no-privileges --no-tablespaces --no-unlogged-table-data --no-comments \
   --no-publications --no-subscriptions --no-security-labels --no-toast-compression --no-table-access-method \
   --schema "${db_schema}" "${db_name}"

tar -cvf "$WORK/$NAME.tar" -C "$WORK" "$NAME"
LOCAL_BYTES=$(stat -c %s "$WORK/$NAME.tar")

echo "[backup] upload s3://$BUCKET/$PREFIX/$NAME.tar ($LOCAL_BYTES bytes)"
aws s3 cp "$WORK/$NAME.tar" "s3://$BUCKET/$PREFIX/$NAME.tar" --region "$REGION" --no-progress

REMOTE_BYTES=$(aws s3api head-object --bucket "$BUCKET" --key "$PREFIX/$NAME.tar" \
                 --region "$REGION" --query ContentLength --output text)
if [[ "$REMOTE_BYTES" != "$LOCAL_BYTES" ]]; then
  echo "[backup] ERROR: uploaded size $REMOTE_BYTES != local size $LOCAL_BYTES" >&2
  exit 1
fi

if [[ "$PROMOTE" == "true" ]]; then
  printf '%s\n' "$NAME.tar" | aws s3 cp - "s3://$BUCKET/$PREFIX/LATEST" --region "$REGION"
  echo "[backup] LATEST -> $NAME.tar"
else
  echo "[backup] LATEST unchanged (promote=false): the pipeline did not succeed"
fi

rm -rf "$WORK"
echo "BACKUP_RESULT file=$NAME.tar bytes=$LOCAL_BYTES promoted=$PROMOTE"
