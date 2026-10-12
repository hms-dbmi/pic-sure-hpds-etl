#!/bin/bash
#
# Prints where the Jenkins server keeps the participant database's credentials for <env>.
# The one place that path is defined: participant-db-start writes the file, participant-db-stop
# deletes it, require-db.sh checks it, and common.mk hands it to DB-backed runners.
#
# The file lives on the Jenkins server, outside every workspace, so all jobs on that server
# share it. Jobs on a different agent would not see it.
#
# Usage: db-credentials-path.sh <env>
#        HPDS_ETL_CREDENTIALS_DIR overrides the directory (default ~/.hpds-etl).
set -euo pipefail

ENV_NAME="${1:?usage: db-credentials-path.sh <env>}"
echo "${HPDS_ETL_CREDENTIALS_DIR:-$HOME/.hpds-etl}/participant-db-$ENV_NAME.json"
