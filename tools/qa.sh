#!/usr/bin/env bash
# QA helper for driving an emulator/device with adb.
#   tools/qa.sh <command> [args]
# Commands:
#   devices                      list connected devices
#   wait                         wait until the device has fully booted
#   install [apk]                install (default: debug APK), replacing any previous install
#   grant                        grant All-files access (MANAGE_EXTERNAL_STORAGE) via appops
#   seed                         copy testdata/* into /sdcard/Documents/StudyNotes
#   reset                        stop app, clear its data and the library folder
#   launch                       start the app
#   stop                         force-stop the app
#   shot <name>                  screenshot -> qa/screens/<name>.png
#   tap <x> <y>                  finger tap
#   swipe <x1> <y1> <x2> <y2> [ms]          finger swipe
#   stylus <x1> <y1> <x2> <y2> [ms]         stylus swipe (TOOL_TYPE_STYLUS)
#   stylustap <x> <y>            stylus tap
#   text <string>                type text (spaces allowed)
#   key <KEYCODE>                key event, e.g. KEYCODE_BACK
#   ui                           dump the UI hierarchy (uiautomator) -> qa/ui.xml and print text/desc + bounds
#   pull-meta                    pull the library's .studynotes folder -> qa/pulled/
#   pull-file <device-path>      pull a file -> qa/pulled/
#   appfiles <path>              list a path inside the app sandbox (debug build: run-as)
#   appcat <path> <out>          copy a file out of the app sandbox (debug build: run-as) -> qa/pulled/<out>
#   crashes                      show recent crashes / errors for the app from logcat
#   log [lines]                  tail the app's logcat
#   size <WxH|reset> [density]   override display size/density (simulate phone/tablet)
set -u
# Stop Git Bash from rewriting device paths like /sdcard into Windows paths.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
ADB="/c/Users/byron/AppData/Local/Android/Sdk/platform-tools/adb.exe"
PKG="com.studynotes.app"
ROOT="/c/Users/byron/Projects/StudyNotesApp"
QA="$ROOT/qa"
LIB="/sdcard/Documents/StudyNotes"
mkdir -p "$QA/screens" "$QA/pulled"
cmd="${1:-}"; shift || true
case "$cmd" in
  devices) "$ADB" devices -l ;;
  wait)
    "$ADB" wait-for-device
    for _ in $(seq 1 180); do
      [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo booted; exit 0; }
      sleep 2
    done
    echo "timeout waiting for boot"; exit 1 ;;
  install)
    APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
    "$ADB" install -r -d -g "$(cygpath -w "$APK")" ;;
  grant)
    "$ADB" shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
    "$ADB" shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE ;;
  seed)
    "$ADB" shell mkdir -p "$LIB"
    for f in "$ROOT"/testdata/*; do "$ADB" push "$(cygpath -w "$f")" "$LIB/" >/dev/null && echo "pushed $(basename "$f")"; done ;;
  reset)
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell pm clear "$PKG"
    "$ADB" shell rm -rf "$LIB" ;;
  launch) "$ADB" shell am start -W -n "$PKG/.MainActivity" ;;
  stop) "$ADB" shell am force-stop "$PKG" ;;
  shot)
    NAME="${1:-shot}"
    "$ADB" exec-out screencap -p > "$QA/screens/$NAME.png" && echo "$QA/screens/$NAME.png" ;;
  tap) "$ADB" shell input tap "$1" "$2" ;;
  swipe) "$ADB" shell input swipe "$1" "$2" "$3" "$4" "${5:-300}" ;;
  stylus) "$ADB" shell input stylus swipe "$1" "$2" "$3" "$4" "${5:-400}" ;;
  stylustap) "$ADB" shell input stylus tap "$1" "$2" ;;
  text) "$ADB" shell input text "$(echo "$*" | sed 's/ /%s/g')" ;;
  key) "$ADB" shell input keyevent "$1" ;;
  ui)
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/ui.xml "$(cygpath -w "$QA/ui.xml")" >/dev/null 2>&1
    python - "$QA/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ''])[1]
    t, d, b, c = g('text'), g('content-desc'), g('bounds'), g('clickable')
    if t or d:
        print(f"{b:28} {'[click] ' if c == 'true' else ''}{t!r} {('desc=' + repr(d)) if d else ''}")
PY
    ;;
  pull-meta) "$ADB" pull "$LIB/.studynotes" "$(cygpath -w "$QA/pulled")" ;;
  pull-file) "$ADB" pull "$1" "$(cygpath -w "$QA/pulled")" ;;
  appfiles) "$ADB" shell run-as "$PKG" ls -la "${1:-.}" ;;
  appcat) "$ADB" exec-out run-as "$PKG" cat "$1" > "$QA/pulled/$2" && echo "$QA/pulled/$2" ;;
  crashes) "$ADB" logcat -d -b crash,main -v brief | grep -iE "FATAL|AndroidRuntime|$PKG|StudyNotes|Exception" | tail -n 80 ;;
  log) "$ADB" logcat -d -v brief | grep -iE "$PKG|StudyNotes|PdfDocumentHandle|Docx|Export|Canvas" | tail -n "${1:-60}" ;;
  size)
    if [ "${1:-}" = "reset" ]; then "$ADB" shell wm size reset; "$ADB" shell wm density reset
    else "$ADB" shell wm size "$1"; [ -n "${2:-}" ] && "$ADB" shell wm density "$2"; fi ;;
  *) sed -n '2,32p' "$0" ;;
esac
