#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_output="$task_root/personal/test-output"
mkdir -p "$test_output"
java com.sun.tools.javac.Main -d "$test_output" \
  "$task_root/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/FoldLayoutPolicy.java" \
  "$task_root/personal/tests/FoldLayoutPolicyTest.java"
java -cp "$test_output" FoldLayoutPolicyTest
python3 "$task_root/personal/tests/test_contracts.py"
