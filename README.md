# Fire Buttons

Remap the app buttons on a Fire TV remote (Netflix, Peacock, Prime Video and the like) to the apps you
actually use. For example, the Netflix button opens SmartTube and the Peacock button opens Stremio.

## Why this exists

Fire OS keeps the remote's app buttons to itself. Its own key policy (`OZ-DCS` / `FireTVKeyPolicyManager`)
takes the press and opens the app tied to the button, or that app's Appstore page if it is missing or
disabled. The key never reaches an accessibility service, so button-remapping apps and launchers cannot
see it.

Fire OS does log every press first (`KeyPressReceiver: Remote button pressed. keyCode: 293`). Fire Buttons
waits for the Appstore to come to the front, reads the last few seconds of the log to find out which button
was pressed, and opens your app instead. For 8 seconds afterwards it pushes back the Appstore's second,
delayed page if that lands on top.

## Tested on

- Hisense 55QD6QF Fire TV (AFTOLCBC82), Fire OS 8.1.8.0 (RS8180.3746N), Android 11, with its stock
  voice remote: Netflix sends keyCode 293, Peacock 299.
- Used alongside [Projectivy Launcher](https://github.com/spocky/miproja1) as Home, after a debloat with
  [cleanyourtv](https://github.com/Gathin23/cleanyourtv).

Other Fire TV models and remotes are untested. Check your buttons' codes as shown below.

## What to expect

- The button opens the Appstore for a moment, then your app. With some apps a brief dim can follow a couple
  of seconds later, when the Appstore's delayed page shows up and is pushed back.
- Works when the app tied to the button (Netflix, Peacock...) is **disabled or not installed**. If it is
  installed and enabled, Fire OS opens it directly and Fire Buttons does nothing.
- A Fire OS update can change the log line or the Appstore and break this.

## Build

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0), with the SDK path in
`local.properties` (`sdk.dir=...`).

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

There are no prebuilt APKs. Build it from source.

## Install

Turn on ADB Debugging (Settings > My Fire TV > Developer Options), then from a computer on the same network:

```bash
adb connect <tv ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant io.github.enlightened_d.firebuttons android.permission.READ_LOGS

# switch the service on, keeping any you already have (Projectivy's, for example)
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services "<what the line above printed>:io.github.enlightened_d.firebuttons/.ButtonService"
# if the line above printed null or nothing, use just:
#   adb shell settings put secure enabled_accessibility_services io.github.enlightened_d.firebuttons/.ButtonService
adb shell settings put secure accessibility_enabled 1
```

Test with the real remote, since a key injected over ADB does not go through the same path. Then turn ADB
Debugging off again.

## Choose your buttons and apps

Edit `app/src/main/res/values/buttons.xml` and rebuild:

```xml
<item>293:org.smarttube.stable</item>   <!-- Netflix button -->
<item>299:com.stremio.one</item>        <!-- Peacock button -->
```

To find a button's code, press it, then run:

```bash
adb shell "logcat -d | grep 'Remote button pressed'"
```

To find an app's package name: `adb shell pm list packages | grep -i <name>`.

## Remove

Settings > Applications > Manage Installed Applications > Fire Buttons > Uninstall.

## Privacy

No INTERNET permission, no analytics. `READ_LOGS` lets it read the device log; it looks only for the
button-press line and keeps nothing.

## Licence

MIT
