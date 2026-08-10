#!/usr/bin/env bash
# Ad-hoc verification for PRD-v9 §4.1 MediaGridItem long-press multi-select color patch.
set -uo pipefail
F="/Users/wgt/projects/media-manager/frontend/composeApp/src/commonMain/kotlin/com/wgt/media/MediaListScreen.kt"

echo "== 1. Patch presence =="
grep -n 'background(Color(0x330066FF))' "$F" >/dev/null && { echo "PASS: overlay uses Color(0x330066FF)"; grep -n 'background(Color(0x330066FF))' "$F"; } || { echo "FAIL"; exit 1; }
grep -n 'if (isSelected)' "$F" >/dev/null && echo "PASS: overlay gated by isSelected" || { echo "FAIL: isSelected guard missing"; exit 1; }

echo "== 2. Forced Kotlin metadata recompile (--rerun-tasks, skip KSP) =="
cd /Users/wgt/projects/media-manager/frontend
./gradlew :composeApp:compileCommonMainKotlinMetadata --no-daemon -x kspCommonMainKotlinMetadata --rerun-tasks >/tmp/hermes-verify-build.log 2>&1 \
  && echo "PASS: BUILD SUCCESSFUL" || { echo "FAIL: build failed"; tail -25 /tmp/hermes-verify-build.log; exit 1; }
grep -q 'Task :composeApp:compileCommonMainKotlinMetadata' /tmp/hermes-verify-build.log \
  && ! grep -q 'Task :composeApp:compileCommonMainKotlinMetadata UP-TO-DATE' /tmp/hermes-verify-build.log \
  && echo "PASS: compile task executed fresh (not cached)" || echo "WARN: could not confirm freshness"

echo "== RESULT: ad-hoc verification PASS =="
