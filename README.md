# StudyNotes

A clean, fast Android app for studying documents with a stylus: open **PDFs and Word (.docx)
files**, **highlight** text, write with a **pen**, and pin **typed notes** to the page. Built for the
**Lenovo Idea Tab / Idea Tab Pro** with the Lenovo Tab Pen, and works on any Android 8.0+ phone or
tablet, with or without a stylus.

## Install (APK)

1. Copy `StudyNotes-1.0.0.apk` to the tablet (USB cable, Google Drive, OneDrive or email).
2. Open it from the Files app. If Android asks, allow **Install unknown apps** for that app, then tap **Install**.
3. Open **StudyNotes** and tap **Allow folder access** (this lets the app see files you copy into its folder).

Updates install the same way, over the old version, and your notes are kept.

## Your library folder

Everything lives in **`Documents/StudyNotes`** on the tablet:

- Copy PDFs/DOCX there from a PC (plug in the tablet over USB → *Internal storage › Documents › StudyNotes*)
  or with the Files app. They appear in the library automatically.
- Or tap **Import** in the app and choose **This device**, **Google Drive** or **OneDrive**.

Your highlights and notes are stored next to the documents in a hidden `.studynotes` folder, so
**back up the whole `StudyNotes` folder** to keep everything. Original files are never modified.

## Using it

| Tool | What it does |
|---|---|
| ✋ View | Scroll and zoom without marking the page |
| Highlighter | Translucent marker; text stays readable. Hold the pen still at the end of a stroke to straighten it. *Snap to text* (on by default) shows the text you are selecting as a grey selection while you drag, then highlights exactly that, lined up with the text and covering whole words. *Text only* (on by default) drops a highlight that finds no text instead of leaving a freehand mark; turn it off to mark blank areas and pictures. *Straight lines* is in its options too |
| Pen | Handwriting and drawing |
| Note | Tap the page to pin a typed sticky note; tap a note to edit it, drag to move it |
| Eraser | Rub over highlights or ink to remove them |

Tap a selected tool again for its colours and sizes.

- **Pen vs finger:** the first time you touch the screen with a stylus, StudyNotes switches to
  pen mode: the pen draws, fingers scroll and pinch-zoom, and your palm is ignored. To draw with
  your finger instead (phones), turn on **Draw with finger** in Settings or the editor's ⋮ menu.
- **Saving:** tap **Save** any time. StudyNotes also **autosaves every 5 minutes** (changeable
  in Settings) and whenever you leave the app. If you go back with unsaved changes, it asks first.
- **Share / export:** ⋮ → **Share annotated PDF** creates a normal PDF with your highlights and
  notes burned in, which opens in any PDF reader. You can send it to Drive ("Save to Drive"), OneDrive,
  email and so on. **Save a copy…** saves it to a folder you choose. The same is in each
  document's ⋮ menu in the library (for a Word document without marks it reads **Share as PDF**).

## Google Drive & OneDrive

StudyNotes uses Android's built-in file picker, where the **Google Drive** and **OneDrive** apps
appear as sources once they're installed and signed in. Tap **Import → Google Drive** (or OneDrive);
in the picker tap **☰** and choose the service. Google Docs are converted to PDF automatically. You
can also use **Open with → StudyNotes** or **Share → StudyNotes** from inside the Drive and OneDrive apps.

(A direct API integration would need a registered Google Cloud / Microsoft Azure app with OAuth
keys tied to a signing certificate; the system picker gives the same access with nothing to set up.)

## Troubleshooting

- **Pen doesn't draw (Lenovo Tab Pen Plus):** keep Bluetooth on and pair the pen in
  *Settings › Pen & keyboard › Pen* (hold the pen button 8 s to pair).
- **Files copied from the PC don't show up:** enable folder access (*Settings → Library folder →
  Allow folder access*), then reopen the app.
- **Password-protected PDF:** StudyNotes asks for the password once and keeps an unlocked working
  copy in the hidden `Documents/StudyNotes/.studynotes` folder (so you aren't asked every time).
  Anyone with access to that folder (e.g. over USB) can read the unlocked copy; delete the document
  in StudyNotes to remove it.

## Limitations

- Word documents are converted to PDF for annotating. Headers and footers aren't shown, text
  boxes may be missing, and floating pictures are placed in line with the text, so complex layouts
  can look different from Word.
- *Snap to text* needs Android 15, or Android 12–14 with a current Google Play system update (the
  Idea Tab's Android 14 qualifies). On older systems highlights stay freehand.
- Documents with a password open after you enter it once; Word files with a password can't be opened.

## Building from source

Requirements: Android Studio (or JDK 17+ and the Android SDK with platform 36).

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

Tests: `./gradlew testDebugUnitTest` (JVM unit tests) and, with a device or emulator connected,
`./gradlew connectedDebugAndroidTest` (on-device tests of the pen input, PDF engine, Word
conversion and export positions).

Release signing uses `keystore.properties` + `studynotes-release.jks` in the project root (both
git-ignored). **Back up both files**: Android only installs an update over an existing install if
it is signed with the same key. Without them, release builds fall back to the debug key.

## Updates and releases

StudyNotes is not on the Play Store; it updates itself from this repository's GitHub releases.
On every start (and whenever it comes back to the front) the app reads the latest release. A newer
one shows a full-screen *Update required* page and nothing else works until it is installed: the
page downloads the APK and hands it to Android's installer, which installs it over the existing app
(same signing key, documents and notes untouched). The first time, Android asks to allow StudyNotes
to install apps. A required update is remembered, so going offline does not get around it; while
no newer release is known, being offline changes nothing.

Publishing a release:

1. Tag the commit: `git tag v1.1.0 && git push origin v1.1.0` (the tag is the version; versionCode
   is derived from it, 1.1.0 → 1001000, so it always increases).
2. The **Release** workflow (`.github/workflows/release.yml`) runs the unit tests, builds the signed
   APK and publishes the GitHub release with `StudyNotes-1.1.0.apk` attached and generated notes.
   Edit the release notes on GitHub if you like; the app shows them on the update page.

The workflow needs these repository secrets (Settings → Secrets and variables → Actions):
`RELEASE_KEYSTORE_BASE64` (`base64 -w0 studynotes-release.jks`), `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and `STUDYNOTES_UPDATE_TOKEN`: a fine-grained personal
access token for this repository only with *Contents: read-only*, which the build stamps into the
APK so installed copies can read releases while the repository is private. When that token expires,
store a new one and publish a new release. A build without a token (a local debug build) never
checks for updates. People need the first APK from you directly; from then on the app updates itself.

Architecture notes for developers are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Copyright

StudyNotes is developed and published by **WireByte Technologies**. © 2026 WireByte Technologies.
All rights reserved; see [NOTICE](NOTICE). Company details shown in the app live in one place,
`app/src/main/java/com/studynotes/app/Publisher.kt` (name, copyright year, and slots for the logo,
website and support address), and appear under **Settings › About**.
