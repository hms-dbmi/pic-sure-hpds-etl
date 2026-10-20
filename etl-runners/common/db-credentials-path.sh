#!/bin/bash
#
# Prints the name of the SSM parameter that holds the participant database's app credentials for
# <env>. The parameter is a SecureString whose value is
#   {"engine":"postgres","host":…,"port":…,"dbname":…,"schema":…,"username":…,"password":…,"container":…}
# This is the one place the name is defined. participant-db-start publishes the parameter (through
# ../participant-db/lib.sh), require-db.sh reads it, and DB-backed runners fetch it at boot.
# Every pipeline run's start rotates the password, so no other copy of it is kept (in particular,
# none on the Jenkins server).
#
# Usage: db-credentials-path.sh <env>
set -euo pipefail

ENV_NAME="${1:?usage: db-credentials-path.sh <env>}"
echo "/hpds-etl/$ENV_NAME/participant-db/app-credentials"
