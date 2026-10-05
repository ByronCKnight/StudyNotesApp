#!/usr/bin/env bash
# Creates an isolated copy of the project for one agent: tools/make-workspace.sh <name>
# The copy lives in .ws/<name>; its Gradle settings are tuned for running several builds side by side.
set -eu
ROOT="/c/Users/byron/Projects/StudyNotesApp"
NAME="$1"
WS="$ROOT/.ws/$NAME"
rm -rf "$WS"
mkdir -p "$WS"
cd "$ROOT"
tar --exclude=./.git --exclude=./.ws --exclude=./.gradle --exclude=./.kotlin --exclude=./app/build --exclude=./build -cf - . | (cd "$WS" && tar -xf -)
cat > "$WS/gradle.properties" <<'PROPS'
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.daemon.idletimeout=900000
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
kotlin.compiler.execution.strategy=in-process
PROPS
echo "$WS"
