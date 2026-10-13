# SHARED: the participant database every DB-backed runner reads and writes, a long-lived
# PostgreSQL container on the Jenkins host. See docs/PARTICIPANT_DB.md.
#
#   make start  [ENV=development] [OVERWRITE=true] [RESTORE_FROM=...] [IF_LAST_RUN_FAILED=continue]
#   make backup [ENV=development] PROMOTE=true|false
#   make status [ENV=development]
#   make test                                   the scripts' tests (stubbed; no runtime or AWS needed)

ENV     ?= development
PROMOTE ?= true

.PHONY: start backup status test

start:
	@OVERWRITE="$(OVERWRITE)" RESTORE_FROM="$(RESTORE_FROM)" IF_LAST_RUN_FAILED="$(IF_LAST_RUN_FAILED)" \
		./start.sh "$(ENV)"

backup:
	@./backup.sh "$(ENV)" "$(PROMOTE)"

status:
	@../common/require-db.sh "$(ENV)"

test:
	@./test/run-tests.sh
