#!/usr/bin/env bash
# Runs Gradle in an isolated agent workspace while limiting how many builds run at once.
# usage: tools/agent-build.sh <workspaceDir> <gradle task> [more tasks/args...]
# Prints a filtered log (errors, warnings in our code, test results, BUILD status). Exit code = Gradle's.
set -u
WS="$1"; shift
SLOTS_DIR="/c/Users/byron/Projects/StudyNotesApp/.ws/.build-slots"
MAX_SLOTS=3
mkdir -p "$SLOTS_DIR"
SLOT=""
while [ -z "$SLOT" ]; do
  for i in $(seq 1 $MAX_SLOTS); do
    d="$SLOTS_DIR/slot$i"
    # Reclaim slots abandoned for more than 20 minutes.
    if [ -d "$d" ] && [ -n "$(find "$d" -maxdepth 0 -mmin +20 2>/dev/null)" ]; then rmdir "$d" 2>/dev/null; fi
    if mkdir "$d" 2>/dev/null; then SLOT="$d"; break; fi
  done
  [ -z "$SLOT" ] && sleep 4
done
trap 'rmdir "$SLOT" 2>/dev/null' EXIT INT TERM
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "$WS" || exit 2
LOG="$WS/.last-build.log"
./gradlew "$@" --console=plain > "$LOG" 2>&1
CODE=$?
grep -E "^e: |^w: file|error:|FAILED|Test.*(PASSED|FAILED|SKIPPED)|tests completed|BUILD (SUCCESSFUL|FAILED)|What went wrong|Exception" "$LOG" | grep -v "^w: .*is deprecated" | head -150
echo "(full log: $LOG, exit code $CODE)"
exit $CODE
