# The permanent pipeline's end-of-run report (pipeline-report.py), run by /Jenkinsfile in
# post { always }. See docs/PERMANENT_PIPELINE.md, "End-of-run report".
#
#   make report ARTIFACTS=downstream-artifacts RUN_ID=<tag> OUT=<dir> [RESULT=SUCCESS]
#   make test       the report against test/fixture (and the orchestrator's input probe, stubbed);
#                   UPDATE=1 rewrites test/expected/ after an intended change

ARTIFACTS ?= downstream-artifacts
OUT       ?= pipeline-report-out
RUN_ID    ?= local
RESULT    ?=

.PHONY: report test

report:
	@python3 ./pipeline-report.py "$(ARTIFACTS)" "$(RUN_ID)" "$(OUT)" $(RESULT)

test:
	@UPDATE="$(UPDATE)" ./test/run-tests.sh
