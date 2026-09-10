#!/usr/bin/env bash
set -euo pipefail


# migrated workflow step 1
set -euo pipefail
P=/tmp/workforce-p10-34026595022/gs2-independent-test-rerun.txt
if [ -f "$P" ]; then
  echo 'INDEPENDENT_TEST_FILE_PRESENT=true'
  cat "$P"
else
  echo 'INDEPENDENT_TEST_FILE_PRESENT=false'
fi
