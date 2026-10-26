# Remaining work (updated 2026-10-06)

## State

- **Release APK:** `dist/StudyNotes-1.0.0.apk`, signed with `studynotes-release.jks` (CN=StudyNotes,
  O=Byron Knight), built with R8. `NewApi` lint is fatal, so a call above minSdk without a version
  check fails the build.
- **Tests:** 367 JVM unit tests (`testDebugUnitTest`) and 60 on-device tests
  (`app/src/androidTest`) pass. One device test (LEGACY-only) is skipped on the API 36 test
  emulator.
- **Review and QA:**
  - Code review: 78 findings, 71 confirmed and fixed.
  - Cross-module integration review: 8 findings, 7 fixed, 1 accepted (below).
  - Device QA on the tablet (API 36) and phone (API 37) emulators, debug and release builds. Every
    finding was fixed and re-tested, except the items under "Known, accepted".
  - Evidence: `qa/` (`tab*`, `ph*`, `review/`); git-ignored, delete when no longer useful.
- **Commits:** nothing is committed to git yet.

## To do

1. **Test on the real Lenovo tablet** (the emulators can't cover these):
   - pen hover;
   - real palm rejection while writing;
   - latency with the motion predictor;
   - pressure;
   - whether the pen's side button reaches the app (it should erase);
   - the docked on-screen keyboard with the note dialog (the tablet emulator has a hardware
     keyboard).
   - Also confirm *Snap to text* is offered. It needs Android 15, or Android 12–14 with the
     SDK extension 13 Play system update.
   - *Find in document* (added after the QA pass above, built and unit-tested but not yet run on
     a device): marks line up with the text, the current hit scrolls into view above the
     on-screen keyboard, Next/Previous wrap, and the LEGACY renderer shows the "needs Android
     15" message instead of the bar.
2. **LEGACY renderer** (Android 8–11, or 12–14 without SDK extension 13): untested on a device.
   - It needs an emulator image of API 30–33. None is installed; it's a ~1 GB SDK download.
   - Run the app and `connectedDebugAndroidTest` there. `textSelection_legacy_isUnsupported`
     then runs instead of being skipped.
3. **Real Google Drive / OneDrive import:** sign in to those apps on a device, then import
   through the picker and use "Open with → StudyNotes" from each app.
4. **Commit** the project to git, and keep `keystore.properties` + `studynotes-release.jks` out
   of it. Both are git-ignored; back them up separately.

## Known, accepted

- **Library lock vs. DOCX conversion:** deleting or renaming a Word document while its thumbnail
  is still converting waits for that conversion. Meanwhile, saves and opens in the library wait
  too. It's a stall of seconds, not data loss.
- **Unlocked copies of password PDFs** stay in `.studynotes` (README explains it).
- **Converter changes move DOCX marks:** a Word document's marks drift if its rendition is ever
  rebuilt by a different `DocxConverter.VERSION`. 1.0.0 ships version 3; see
  ARCHITECTURE.md §4 ("Converter changes move DOCX marks") before changing the converter.
- **Cosmetic:** after *Go to page*, the previous page's faint "Page N" placeholder can show
  through the translucent top bar.
- **Cosmetic:** the library's large title doesn't collapse or expand when the app scrolls the
  grid to newly imported documents.
- **DOCX:** headers and footers aren't shown, text boxes may be missing, and floating pictures are
  placed inline (README "Limitations").

## How to run things

- **Build:** `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug assembleRelease testDebugUnitTest`
- **Device tests:** with one device or emulator attached, `./gradlew connectedDebugAndroidTest`.
  With several, set `ANDROID_SERIAL` or use `adb -s <serial> shell am instrument -w
  com.studynotes.app.test/androidx.test.runner.AndroidJUnitRunner` after installing both APKs.
- **Emulators:**
  - `emulator -avd StudyNotes_Tablet -port 5554` (Idea Tab-like, API 36)
  - `emulator -avd Medium_Phone -port 5556` (API 37)
  - `emulator -avd StudyNotes_Test -port 5558` (small API 36 device, for the instrumented tests)
- **QA helper:** `ANDROID_SERIAL=emulator-5554 bash tools/qa.sh <command>` (see the script header).
- **Test documents:** `python tools/make_fixtures.py` regenerates `testdata/`. Copy any changed
  files into `app/src/androidTest/assets/` as well.
