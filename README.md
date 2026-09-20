# Tiny Finger Stopper

An Android app that locks the screen *on* so a child can watch a video without
their fingers changing what is happening.

Start a video, arm the lock from a Quick Settings tile, and hand the phone over.
Touches stop reaching the app underneath and the screen stays awake. Getting out
takes a deliberate two-handed gesture — or a restart, which always cancels the
lock.

Personal-use app, meant to be sideloaded.

## Two modes

**Overlay lock** — works over *any* app: the YouTube app, Disney+, a game. A
transparent system overlay swallows every touch. This is the flexible mode.

**Kid Player** — YouTube only, and the stronger of the two. The video plays inside
this app, which means it can use Android's own screen pinning to block Home,
Recents and the notification shade outright. Turn on **Ask for PIN before
unpinning** in Android's security settings and leaving it needs your device PIN.

## What Android will and will not let an app block

Being straight about this, because no app can block everything:

| Escape route | Overlay lock | Kid Player |
|---|---|---|
| Taps and swipes on the video | Blocked | Blocked |
| Screen timing out | Blocked | Blocked |
| Volume keys | Not blocked | Blocked |
| Notification shade | Partly, varies by device | Blocked by pinning |
| Home / Back / Recents | Not preventable — snaps back instead | Blocked by pinning |
| Power button, reboot | Not blocked, on purpose — that is the way out |

The bottom-edge Home gesture cannot be excluded by any ordinary app. Rather than
pretend otherwise, Overlay lock heals: with usage access granted it notices the
foreground app changed and brings the video straight back, usually within a second.

## Unlocking

Put one finger in one corner and another in the **diagonally opposite** corner,
and hold for two seconds. A small hand cannot span the diagonal of a phone, and
nothing is drawn on screen until the hold is already well underway, so there is no
affordance to discover by mashing.

There are four ways out, by design:

1. The two-corner hold.
2. The **Unlock** action on the ongoing notification.
3. **Restarting the phone.**
4. Auto-unlock, after 90 minutes by default.

## A restart always cancels the lock

This is the requirement the app is built around, because getting it wrong hands
someone a phone they cannot use. The guarantee is structural, not defensive:

- Whether the lock is armed lives **only in memory**. Nothing writes it to disk.
- The app holds **no boot permission and registers no boot receiver**. Nothing of
  ours runs at startup at all.
- The lock service returns `START_NOT_STICKY` and stops itself if the system hands
  it a null intent, so a low-memory kill never resurrects an armed lock.
- The overlay window belongs to the process. When the process goes, so does it.

`LockSafetyTest` fails the build if any of that stops being true. See
[`BootSafety.kt`](app/src/main/java/com/loudmusic/tinyfingerstopper/lock/BootSafety.kt).

If everything else fails, booting into Safe Mode disables third-party apps.

## Setup

Install the APK, open the app, and work down the list:

1. **Display over other apps** — required, this is the touch blocker.
2. **Notifications** — required, the ongoing notification keeps the lock running.
3. **Usage access** — optional, enables snap-back.
4. **Quick Settings tile** — optional but the point of the thing; the app can add
   it for you on Android 13+.
5. **Ask for PIN before unpinning** — for Kid Player, strongly recommended.

You can also share a YouTube link into Tiny Finger Stopper from any app to open it
straight into Kid Player.

## Building

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # unit tests, including the reboot-safety guards
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Every push to `main` builds a debug APK in CI and attaches it to the run as an
artifact, so you can install straight from GitHub without a local Android SDK.

Requires JDK 17, minSdk 26, targetSdk 35. The app has **no runtime dependencies** —
everything it uses is in the framework, which keeps something that holds an overlay
permission small enough to read end to end.

## Testing checklist

The ones that actually matter, on a real device:

- [ ] `adb reboot` while armed — must come back **unlocked**
- [ ] `adb shell am force-stop com.loudmusic.tinyfingerstopper` — overlay gone, tile inactive
- [ ] Low-memory kill — must **not** come back armed
- [ ] Power button off, then on — phone unlocks normally, lock still armed
- [ ] Rotation, split screen, notch coverage
- [ ] Install-over update — service dies, must not self-restore

## Not done yet

- PIN as an alternative to the corner hold (`Prefs` already stores one).
- Auto-disarm on an incoming call.
- A settings screen for hold duration and auto-unlock timeout.
