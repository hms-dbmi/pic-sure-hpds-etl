# The migration pipeline's final report (migration-report.py), run by Jenkinsfile.migration in
# post { always }. TEMPORARY: deleted with the migration.
#
#   make report ARTIFACTS=downstream-artifacts RUN_ID=<tag> OUT=<dir> [RESULT=SUCCESS]
#   make test       the report against fixtures built by test/run-tests.sh

ARTIFACTS ?= downstream-artifacts
OUT       ?= migration-report-out
RUN_ID    ?= local
RESULT    ?=

.PHONY: report test

report:
	@python3 ./migration-report.py "$(ARTIFACTS)" "$(RUN_ID)" "$(OUT)" $(RESULT)

test:
	@./test/run-tests.sh
