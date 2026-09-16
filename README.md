# Ad Log Overlay

A floating, debug-only readout of what an Android app's ads are doing, drawn on top of whatever
screen is showing. Tap the `AD` bubble for a panel of colour-coded ad events — `REQUESTED`,
`LOADED`, `SHOWN`, `FAILED` — each stamped with the ad slot it is about, the screen it fired on,
and, for failures, a plain-English reason. If the app uses Firebase Remote Config, a second tab
shows the settings the app is receiving, written so anyone can read them.

"Why did no ad appear?" is usually answered by scrolling logcat on a laptop while someone else
holds the phone. This answers it on the device, in the tester's hand.

```
11:54:31  REQUESTED   Home_NATIVE_AD  (native)      @MainActivity
11:54:31  LOADED      Home_NATIVE_AD  (native)      @MainActivity
11:54:33  SHOWN       Home_NATIVE_AD  (native)      @MainActivity
11:54:40  FAILED      Checkout_INTER_AD  (interstitial)   @CheckoutActivity
                      ↳ no fill (request OK, but no inventory) [code 3]
```

- **No code to add.** One dependency line; it installs itself.
- **Debug builds only.** Added with `debugImplementation`, it is not in a release build at all.
- **No permissions.** It draws inside the app's own screens, not as a system window.
- **No AndroidX.** Its only runtime dependency is `kotlinx-coroutines-core`.
- **minSdk 24.**

---

## Install

The library is published to **GitHub Packages**. GitHub requires a token to download any package
from it, even a public one, so this takes two parts.

**1. Credentials, once per machine.** Create a GitHub personal access token (classic) with the
`read:packages` scope and add it to `~/.gradle/gradle.properties` — never to the project:

```properties
gpr.user=your-github-username
gpr.key=ghp_your_token
```

On CI, set `GITHUB_USER` and `GITHUB_TOKEN` instead.

**2. The repository and the dependency.**

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hussainasad-bot/adlogoverlay")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_USER")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
            // Only this library is looked up here, so other dependencies don't slow down.
            content { includeGroup("com.9dtechnologies") }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("com.9dtechnologies:adlogoverlay:1.0.0")
}
```

That is the whole integration: no code. Run a debug build and the `AD` bubble is on every screen.
Available versions are listed under **Packages** on the repository page.

Use `debugImplementation`, never `implementation`: it keeps the library out of release builds
entirely, so there is nothing for R8 to strip and nothing that can reach users.

---

## Using it

The overlay starts collapsed as a small `AD` bubble.

| Control | What it does |
|---|---|
| Tap the bubble | Opens the panel |
| Drag the bubble | Moves it; on release it snaps to the nearer screen edge at the height you left it |
| Drop the bubble on the ✕ | A ✕ rises near the bottom while you drag. Drop the bubble on it to close the overlay on every screen |
| Shake the phone | Brings the overlay back, with the panel open, after it was closed |
| Tap a `▾` / `▸` line (FRC tab) | Folds or unfolds that setting, ad placement or nested object |
| Drag the `⠿` header | Moves the panel up and down |
| Drag the log area | Scrolls back through history |
| `ADS` / `FRC` tabs | Switch between the ad log and the app's Remote Config. `FRC` appears only if the app uses Firebase Remote Config |
| `REFRESH` (FRC tab) | Downloads the latest settings from Firebase now — see [below](#refresh) |
| `FIND` | Searches whichever tab is showing; matches are underlined |
| `FILTER` | Filters the log by event, screen, ad slot or error |
| `QA` | Plain-language mode: only the moments of an ad's life |
| `CLEAR` | Drops every filter at once |
| `✕` (header) | Collapses back to the bubble |

A summary line under the header always names the filters in effect, so a filter left on earlier
never looks like the ads went quiet.

### QA mode

`QA` narrows the log to the story of one ad:

```
REQUESTED → LOADED → SAVED → SHOWN      (FAILED when it goes wrong)
```

plus `BLOCKED` — "the app never even asked, a rule stopped it", the most common answer to "why
did no ad appear". It hides raw log tags and drops milliseconds from the clock.

### Closing it completely

Drag the bubble and a ✕ target rises near the bottom. Bring the bubble close: it snaps onto the
target, the target turns red and the phone ticks, so you know letting go will close it. Release
and the overlay leaves every screen, with a message saying how to get it back.

**Shake the phone to bring it back, with the panel open** — three firm jolts. Setting the phone down or walking does
not count. Relaunching the app always brings it back too. On a device with no accelerometer the ✕
is never offered, so the overlay cannot be closed with no way back.

---

## The FRC tab

Shown when the app uses Firebase Remote Config. It lists the settings the app is using right now,
in plain words:

```
FIREBASE REMOTE CONFIG
Settings this app gets from Firebase  ·  checked 15:00:12

Last download  ✓ Downloaded  ·  14:59:02 (1 minute ago)
Settings       12 in total  ·  12 from Firebase  ·  0 built-in
Download rule  Can re-download any time  ·  waits up to 1 minute
Refresh        Tap REFRESH to download the latest settings

▾ Ads config  FROM FIREBASE
ads_config
  ▾ Home native ad  Home_NATIVE_AD
      Ad type                 Native (2)
      Show loading before ad  No
      Ad ID                   ca-app-pub-3940256099942544/2247696110 (Google test ad)

Show onboarding  BUILT-IN DEFAULT
show_onboarding
  Yes
```

**Fold what you are not looking at.** Tap any `▾` line to fold it and `▸` to open it again: a
setting's title folds the whole setting, a placement folds that placement, a nested object folds
just that object. A folded line says how many lines it hides, and "has matches" when a search hit
is inside. Folds stay as you left them across `REFRESH`, searches and screen changes.

**Read the top block first.** "Last download" says whether anything below came from Firebase at
all. Anything other than `✓ Downloaded` comes with a line saying what you are seeing instead.

| Tag | Meaning |
|---|---|
| `FROM FIREBASE` | Downloaded from Firebase and in use — what was set in the console |
| `BUILT-IN DEFAULT` | The app's own fallback: nothing arrived for this setting, or nothing has been downloaded yet |
| `NOT SET` | No value at all. Rarely seen |

### How values are made readable

| Stored as | Shown as |
|---|---|
| `isShowLoadingBeforeAd`, `HOME_NATIVE_AD` | "Show loading before ad", "Home native ad" |
| `true` / `false` | **Yes** in green / **No** in red |
| `null`, `""` | (not set), (blank) |
| A list of objects | One group per item, named by its `adName` / `name` / `title` … field, with the raw name beside it. Otherwise "Item 1", "Item 2" |
| A nested object | A heading with its settings indented beneath |
| A short list of plain values | One line: `a, b, c` |
| `1500` in a setting named `…InMs` | `1500 (1.5 seconds)` — only when the name states the unit |
| A code the app has named | `Native (2)` — see [`remoteConfigValueLabels`](#configuration) |
| A Google test ad unit ID | `… (Google test ad)` |
| Text that looks like another type — `"true"`, `"7000"` | `true (as text)`, because `"true"` and `true` are different JSON types |
| Broken JSON, or any other text | Exactly as received |

Each setting's exact key is shown dimmed under its title, for looking it up in the Firebase
console. `FIND` matches both forms: `isShowLoadingBeforeAd` and "show loading" find the same
setting, and in a list only the matching items are shown.

### When it reads, and when it downloads

The tab never polls, never runs on a timer and never opens a live connection.

| You… | It… | Network? |
|---|---|---|
| Switch to `FRC`, tap it again, reopen the panel on it, or change screen with it open | Reads the settings the app is using now | No |
| Tap `REFRESH` | Downloads from Firebase, applies the result, reads again, and tags every setting that changed `CHANGED` | **Yes** |

### REFRESH

`REFRESH` downloads into the **app's own** Remote Config (`fetch(0)` then `activate()`):

- `fetch(0)` rather than `fetchAndActivate()`, which respects the app's minimum interval and can
  quietly return a cached copy.
- The app's own instance rather than a private copy, because percentile rollouts, A/B variants
  and audiences are decided per installation — only the app's own instance shows what *this*
  install really receives.

**The trade-off:** the app uses the downloaded values from that moment, just as it would after its
next launch. Code that already read and cached a setting keeps its old copy until it reads again,
so a `CHANGED` setting may not affect the app until it is relaunched.

---

## Configuration

Everything has a default, and none of it is needed. To use your app's own colours, extra log tags
or names for coded values, call `install` with an `AdLogConfig` from `Application.onCreate`; it
replaces the defaults.

Because the library exists only in debug builds, shared code cannot reference it. Put a small
object in `src/debug` and an empty twin in `src/release`, and call it from `Application.onCreate`:

```kotlin
// src/debug/kotlin/com/example/AdLogSetup.kt
object AdLogSetup {
    fun install(app: Application) = AdLogOverlay.install(
        app,
        AdLogConfig(
            surfaceColor = R.color.surface,
            headingColor = R.color.text_primary,
            extraTags = setOf("myadmanager"),
            remoteConfigValueLabels = mapOf(
                "adType" to mapOf("1" to "Banner", "2" to "Native", "3" to "Interstitial")
            )
        )
    )
}

// src/release/kotlin/com/example/AdLogSetup.kt
object AdLogSetup {
    fun install(app: Application) = Unit
}
```

| Field | Default | Notes |
|---|---|---|
| `surfaceColor` | `R.color.adlog_surface` | The panel's background. Also decides which event colours are used |
| `headingColor` | `R.color.adlog_heading` | Header, chips, bubble text |
| `subtextColor` | `R.color.adlog_subtext` | Secondary text and the panel's edge |
| `borderColor` | `R.color.adlog_border` | Search field and chip outlines |
| `containerColor` | `R.color.adlog_container` | Search field and chip fills |
| `extraTags` | `emptySet()` | Log tags your ad code uses that **do not** contain "ad". Matched as lowercase substrings, so give them in lowercase |
| `readLogcat` | `true` | `false` stops reading logcat; send events with `AdLogStore.post` instead |
| `screenNameOf` | the Activity's class name | How screens are named in the log and the screen filter |
| `remoteConfigValueLabels` | `emptyMap()` | Names for coded Remote Config values, by field then value |

Colours are resource ids, so `values-night` still applies and the overlay follows the app's theme.

### Sending events directly

By default the overlay reads the app's own logcat, so it needs no change to your ad code. To send
events yourself — with `readLogcat = false`, or alongside it:

```kotlin
AdLogStore.post(AdLogKind.LOADED, "Home_NATIVE_AD", "onAdLoaded")
```

### Turning off the automatic install

The library installs itself through a `ContentProvider` in its manifest, which Android starts
before `Application.onCreate`. To install it yourself instead, remove the provider:

```xml
<provider
    android:name="com.ninedtechnologies.adlogoverlay.AdLogInitProvider"
    tools:node="remove" />
```

---

## How it works

**Reading the log.** It reads the app's own logcat (`logcat --pid=<own pid>`). An app may always
read its own logs, and cannot read any other app's, so no permission is involved. A line is kept
when its tag contains "ad" or one of `extraTags`, or its message uses ad vocabulary —
`interstitial`, `native`, `banner`, `rewarded`, `impression`, `mediation`, `no fill`, … The Mobile
Ads SDK's own network tracing is dropped as noise.

**Classifying.** Each line gets one of 15 kinds — `REQUESTED`, `LOADED`, `FAILED`, `SHOWN`,
`CLOSED`, `IMPRESSION`, `CLICKED`, `REVENUE`, `REWARDED`, `SAVED`, `EXPIRED`, `REMOVED`, `BLOCKED`,
`SDK`, and `·` for anything unclassified — each with a colour legible on light and dark panels.
The ad slot is picked out of the message by pattern, and a failure's reason is decoded from the
Mobile Ads SDK's error codes (0 internal, 1 invalid request, 2 network, 3 no fill, 8 app ID
missing, 9 mediation no fill, 10 request ID mismatch, 11 invalid ad string) or from common gate
names such as `PremiumUser` and `AdSessionLimitReached`.

**Staying light.** A busy ad stack can log over a hundred lines a second, so the overlay limits
itself:

| Limit | Value |
|---|---|
| Lines kept in history | 400 |
| Lines taken in per second | 25 — past that, one "+N more lines suppressed" line |
| Longest message kept | 220 characters |
| Redraws | at most 4 a second, appended in place |
| While collapsed | lines are still recorded, but never drawn |

**Screens.** The panel is a view added to each Activity's own content, attached on `onStart` and
removed on `onStop`, so the next screen's panel is up before the last one leaves. It never takes a
touch it does not need: the app underneath stays fully usable.

**Remote Config without a Firebase dependency.** `firebase-config` is a compile-only dependency.
The library checks for Firebase Remote Config in the app at runtime; the `FRC` tab exists only
when it is found, and an app without Firebase gains no dependency.

---

## Troubleshooting

**No bubble.** Check the dependency is `debugImplementation` and you are running a debug build. If
it was dropped on the ✕, shake the phone or relaunch the app. If you removed the provider, call
`AdLogOverlay.install(app)` yourself.

**The panel is empty.** Tap the bubble — it starts collapsed. Check the summary line for a filter
left on, and tap `CLEAR`. Your ad code must log something; if its log tags do not contain "ad",
add them to `extraTags` in lowercase.

**A line shows as `·`.** It looked ad-related but matched no kind.

**No ad slot name on a line.** The slot is recognised when its name mentions a format, such as
`Home_NATIVE_AD` or `checkout_banner`.

**There is no `FRC` tab.** The app does not include `com.google.firebase:firebase-config`.

**"Firebase hasn't started yet".** Firebase was not initialised when the tab read. Tap `FRC` again.

**Every setting is `BUILT-IN DEFAULT`.** Check "Last download": the app has not downloaded yet,
the download failed, or it downloaded but never applied it. Tap `REFRESH`.

**`REFRESH` says "Asked Firebase too often".** Firebase limits how often one installation may
download. Wait a few minutes.

**A setting is `CHANGED` but the app behaves the old way.** The app read and cached it earlier.
Relaunch the app.

---

## Publishing a new version

For maintainers. One command runs the unit tests and, only if they pass, publishes to this
repository's GitHub Packages:

```bash
./gradlew publishNewVersion -Pbump=patch    # 1.0.0 -> 1.0.1
./gradlew publishNewVersion -Pbump=minor    # 1.0.0 -> 1.1.0
./gradlew publishNewVersion -Pbump=major    # 1.0.0 -> 2.0.0
./gradlew publishNewVersion                 # publishes VERSION_NAME as it is
```

A bump is written back to `VERSION_NAME` in `gradle.properties` only after the upload succeeds —
commit that change. GitHub Packages refuses to overwrite a version that already exists, so
running it twice for the same version fails rather than replacing a release.

It needs `gpr.user` and `gpr.key` in `~/.gradle/gradle.properties`, with a token that has
`write:packages` and write access to this repository. Without them it stops before uploading
and says so.

`GITHUB_REPO` in `gradle.properties` decides where it publishes; change it if the repository moves.

### Building locally

```bash
./gradlew :adlogoverlay:testDebugUnitTest :adlogoverlay:assembleRelease
./gradlew publishToMavenLocal     # into ~/.m2, for trying it in another project
```

### Maven Central (not set up)

The build can also publish to Maven Central, through the **Publish to Maven Central** workflow,
which runs when a `v*` tag is pushed. It needs, once: a
[Central Portal](https://central.sonatype.com/) account, the `com.9dtechnologies` namespace
[verified](https://central.sonatype.org/register/namespace/) with a DNS TXT record on
`9dtechnologies.com`, a [Portal user token](https://central.sonatype.org/publish/generate-portal-token/)
as the secrets `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`, and a
[GPG key](https://central.sonatype.org/publish/requirements/gpg/) as `SIGNING_KEY` /
`SIGNING_KEY_PASSWORD`. **Until then, don't push `v*` tags** — the workflow will fail for lack of
those secrets. It uploads without releasing: a version published to Maven Central can never be
changed or deleted, so someone presses **Publish** on central.sonatype.com by hand.

---

## License

```
Copyright 2026 9D Technologies

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
