# Lights Out

An Android app for a sleep challenge between friends. It tracks **attempted sleep time**: from the moment you tap "start" at bedtime until your first alarm, minus the time you spent on your phone. You choose each night whether video watching counts.

Everything is computed and stored on the phone. Only the summary text you choose to share leaves it.

## Install (you and your friend)

1. Open the latest [release](../../releases) on the phone and download `lights-out.apk`.
   To get updates automatically, add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) instead.
2. Open the APK. Android asks you to allow installing from your browser (or Obtainium); allow it.
3. Open **Lights Out** and follow the setup cards:
   - **Usage access** (required): lets the app read when the screen was on.
   - **Notifications** (optional): your result shortly after your alarm.
4. Settings → **Add Quick Settings tile**, so bedtime is one tap.
5. Agree with your friend on the same settings (glance threshold, max night length, video apps).

## How a night works

- **Start:** tap the tile (or "Start sleeping" in the app). The end of the night is your next alarm, as set in the Clock app or any alarm app that shows in the status bar.
- **No alarm found** (none set, or the next one is further away than the max night length, e.g. Friday night with weekday alarms): the app asks when you expect to wake up. Tapping the tile opens that prompt.
- **During the night** nothing runs in the background. If you change your alarm, the night follows the first alarm: an earlier alarm moves the end forward, a moved alarm moves it back, and dismissing the upcoming alarm ends the night at that moment. "I'm up" ends it early.
- **Morning:** shortly after the alarm, the app reads Android's usage history for the night and computes:

  ```
  attempted = (wake − start) − screen use − (video, unless it counts tonight)
  ```

  | Screen state | Counts as |
  | --- | --- |
  | Off | sleep attempt |
  | Lock screen on for less than the glance threshold (default 30 s), e.g. checking the time | sleep attempt (shown as "glance") |
  | Lock screen on for longer | screen use |
  | Unlocked, a video app in front | video |
  | Unlocked, any other app | screen use |

  The screen-on time right after you tap "start" counts as screen use, so the night effectively starts when the screen goes dark.

- **Video toggle:** set on the start screen and locked once the night starts. The video app list and other settings are copied into the night at start, so editing them in the morning can't change a finished night.
- **Max night length** (default 12 h): time earlier than this before the wake-up time isn't counted. Tapping start at 20:00 for a 07:00 alarm counts from 19:00. Alarms further away than this are treated as no alarm.
- **Share:** each past night has a share button that produces a summary like:

  ```
  🌙 Alex · night of Tue 27 Oct
  Attempted sleep: 7h 12m
  23:18 → 07:00 (alarm, Clock)
  Screen use −18m · video 25m counted
  7-night average: 7h 01m
  ```

## Limitations

- **Honor system.** The app can't tell whether you actually tried to sleep, only that your phone was idle. Watching TV or reading a book counts as an attempt. Starting hours before the alarm is only limited by the max night length.
- **Video means "a video app is in front"**, not "a video is playing". Scrolling YouTube Shorts counts as video; YouTube paused while you read comments still counts as video.
- **Alarm detection** covers alarms set with Android's alarm-clock API (Google Clock, Samsung Clock and most alarm apps). Some apps, e.g. reminder or automation apps, also use it; the start screen shows which app the alarm comes from. An alarm app that doesn't use it isn't seen, so you'd be asked for a time instead.
- **Usage history is kept by Android for a few days.** If a night can't be computed (usage access revoked), open the app within a day or two of granting access again.
- Lock and unlock events are recorded from Android 9. If a phone doesn't record them, all screen-on time counts as screen use, including glances.

## Signing key (one-time, for the repository owner)

Android only installs an update over an existing app if both are signed with the same key. The workflow only publishes releases signed with your own key:

```sh
keytool -genkeypair -v -keystore lights-out.jks -alias lightsout \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 lights-out.jks   # macOS: base64 -i lights-out.jks
```

Add these under the repository's Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | output of the `base64` command |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `lightsout` |
| `KEY_PASSWORD` | the key password (same as the keystore password unless you set another) |

Keep `lights-out.jks` somewhere safe. Then re-run the **Build APK** workflow or push to the default branch: each push to the default branch publishes a release. Builds without the secrets still produce a downloadable artifact on the workflow run, signed with a throwaway key, which is fine for testing but can't be updated in place.

## Development

- Kotlin, Jetpack Compose, WorkManager; min Android 9, target Android 15.
- `app/src/main/java/app/lightsout/core/` is plain Kotlin (screen-time analysis, wake-time maths) with JVM unit tests: `./gradlew testDebugUnitTest`.
- `./gradlew assembleDebug` builds a debug APK (needs the Android SDK; Android Studio provides one).
