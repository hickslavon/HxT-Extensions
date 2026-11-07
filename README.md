# HxT Extensions (personal)

Personal source extensions for TachiHxT, built on the Keiyoushi extensions framework
(Apache-2.0, see LICENSE). Only our own sources are loaded; see `settings.gradle.kts`.

## Build
Open this folder in Android Studio, let Gradle sync, then in the Terminal tab:

    .\gradlew.bat :src:all:hxtnhentai:assembleDebug

The APK is in `src/all/hxtnhentai/build/outputs/apk/debug/`.

Signing: with no `signingkey.jks` in the project root, the build uses your normal Android
debug keystore (the same key TachiHxT is signed with), so you only trust it once.

## Install
Copy the APK to the phone and install it. In TachiHxT: Browse > Extensions > Trust
"NHentai (HxT)". Turn on NSFW sources in Browse settings if it does not show.

## Adding another source
Create `src/all/<name>/` like `hxtnhentai` and add
`loadIndividualExtension("all", "<name>")` in `settings.gradle.kts`.

## If nhentai changes
Image/thumbnail hosts are two constants at the top of `Dto.kt`.

## Publishing your personal repo (GitHub)
1. Build: `.\gradlew.bat :src:all:hxtnhentai:assembleDebug`
2. Generate the repo files: `powershell -ExecutionPolicy Bypass -File .\scripts\make-repo.ps1`
3. Commit + push (the `repo/` folder is what the app reads).
4. In the app, add: `https://raw.githubusercontent.com/<user>/<repo>/main/repo/index.min.json`

To release an update: raise `versionCode` in the extension's `build.gradle.kts`, repeat 1-3.
Always build on the same computer/key, or Android will refuse to update the installed extension.
