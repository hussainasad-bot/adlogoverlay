# Ad Log Overlay

A floating, debug-only readout of what an Android app's ads are doing, drawn on top of whatever
screen is showing. Tap the `AD` bubble for a panel of colour-coded ad events — `REQUESTED`,
`LOADED`, `SHOWN`, `FAILED` — each stamped with the ad slot it is about, the screen it fired on,
how long the load took and, for failures, a plain-English reason. A `STATS` tab adds up each ad
slot, and if the app uses Firebase Remote Config an `FRC` tab shows the settings it is receiving,
written so anyone can read them.

"Why did no ad appear?" is usually answered by scrolling logcat on a laptop while someone else
holds the phone. This answers it on the device, in the tester's hand.

```
11:54:31  REQUESTED   Home_NATIVE_AD  (native)  @MainActivity
11:54:32  LOADED      Home_NATIVE_AD  (native)  in 1.2s  @MainActivity
11:54:33  SHOWN       Home_NATIVE_AD  (native)  @MainActivity
── MARK 1 ─────────────────────────────── 11:54:36
11:54:39  SHOWN       Checkout_INTER_AD  (interstitial)  @CheckoutActivity
11:54:40  FAST CLICK  Checkout_INTER_AD  (interstitial)  @CheckoutActivity
                      ↳ clicked 0.4s after it appeared - likely accidental
11:54:52  FAILED      APP_OPEN  (app open)  @MainActivity
                      ↳ no fill (request OK, but no inventory) [code 3]
SCREEN  CheckoutActivity › PaymentFragment › BottomSheetDialog “Pay with”
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
`read:packages` scope — [step by step below](#creating-a-classic-token) — and add it to
`~/.gradle/gradle.properties`, never to the project:

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
    debugImplementation("com.9dtechnologies:adlogoverlay:1.0.1")
}
```

That is the whole integration: no code. Run a debug build and the `AD` bubble is on every screen.
Available versions are listed under **Packages** on the repository page.

Use `debugImplementation`, never `implementation`: it keeps the library out of release builds
entirely, so there is nothing for R8 to strip and nothing that can reach users.

### Creating a classic token

GitHub Packages only accepts a **personal access token (classic)** — a fine-grained token will not
work. You need one per person, created once:

1. On GitHub, open your profile picture menu → **Settings**.
2. In the left sidebar, open **Developer settings**.
3. Under **Personal access tokens**, choose **Tokens (classic)**
   ([github.com/settings/tokens](https://github.com/settings/tokens)).
4. Click **Generate new token** → **Generate new token (classic)**.
5. **Note:** a name that says what it is for, such as `adlogoverlay gradle`.
6. **Expiration:** pick one. When it expires, builds fail to download the library until you
   create a new token and replace `gpr.key`.
7. **Select scopes** — tick only what you need:
   - `read:packages` — to **use** the library in an app. Enough for everyone except maintainers.
   - `write:packages` — to **publish** new versions ([Publishing](#publishing-a-new-version)).
8. Click **Generate token**, then copy it with the copy icon. **GitHub shows it only once**; if you
   lose it, generate a new one.
9. If the repository belongs to an organization that uses SAML single sign-on, also authorize the
   token for that organization — see GitHub's
   [Authorizing a personal access token for use with single sign-on](https://docs.github.com/en/enterprise-cloud@latest/authentication/authenticating-with-saml-single-sign-on/authorizing-a-personal-access-token-for-use-with-saml-single-sign-on).

Paste it as `gpr.key` in `~/.gradle/gradle.properties` (on Windows,
`C:\Users\<you>\.gradle\gradle.properties`). That file is outside every project, so the token is
never committed. Treat it like a password: never paste it into code, chat or a ticket, and revoke it
on the same page if it leaks.

---

## Using it

The overlay starts collapsed as a small `AD` bubble.

| Control | What it does |
|---|---|
| Tap the bubble | Opens the panel |
| Drag the bubble | Moves it; on release it snaps to the nearer screen edge at the height you left it |
| Drop the bubble on the ✕ | A ✕ rises near the bottom while you drag. Drop the bubble on it to close the overlay on every screen |
| Shake the phone | Brings the overlay back, with the panel open, after it was closed |
| Tap a `▾` / `▸` line (STATS, FRC) | Folds or unfolds that ad slot, setting, placement or nested object |
| Drag the `⠿` header | Moves the panel up and down |
| Drag the log area | Scrolls back through history |
| `ADS` / `STATS` / `FRC` tabs | The ad log, the numbers per ad slot, and the app's Remote Config. `FRC` appears only if the app uses Firebase Remote Config. A red dot on `STATS` means a fast click was seen |
| `REFRESH` (FRC tab) | Downloads the latest settings from Firebase now — see [below](#refresh) |
| `FIND` | Searches the log or the config; matches are underlined |
| `FILTER` | Filters the log by event, screen, ad slot or error |
| `QA` | Plain-language mode: only the moments of an ad's life |
| `TOOLS` | Mark, clear or share the log; open Ad Inspector; check or reset consent — see [TOOLS](#tools) |
| `CLEAR` (summary line) | Drops every filter at once |
| `✕` (header) | Collapses back to the bubble |

A summary line under the header always names the filters in effect, so a filter left on earlier
never looks like the ads went quiet. The `SCREEN` line at the bottom always says where you are.

### What the log adds

| Line | Means |
|---|---|
| `LOADED … in 1.2s` | Time from the slot's `REQUESTED` line to this load |
| `FAST CLICK` (red) | A click less than 1 second after the ad appeared — likely accidental, the kind AdMob can count as invalid traffic. The line under it gives the exact gap |
| `── MARK 2 ──── 11:54:36` | A divider you added with `TOOLS` → `MARK`. Filters never hide it |

`QA` mode keeps fast clicks too.

### The SCREEN line

The last line of the panel names where you are, as deeply as it can tell:

```
SCREEN  MainActivity › AllFilesFragment › BottomSheetDialog “Sort by”
```

- **Activity** — always.
- **Fragment** — the AndroidX fragment on screen, if the app uses them. A container such as a
  NavHostFragment gives way to the page inside it; `+1` means two are showing at once.
- **Dialog or popup** — on Android 10 and newer. A dialog is named by its class and its first line
  of text, so a plain `Dialog` still says which one it is. `Popup` covers menus and dropdowns.

It updates every second while the panel is open, and never while it is collapsed.

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

## The STATS tab

One block per ad slot, counted from the same lines as the log, problems first — fast clicks, then
a low fill or show rate or an expired ad, then an ad about to expire:

```
SESSION      14 min  ·  19 requested  ·  10 loaded  ·  8 shown
FAST CLICKS  2  ·  Checkout_INTER_AD
CACHED NOW   2 ads  ·  1 expiring soon

▾ Checkout_INTER_AD  interstitial                      ● cached 3m
  requested 4  ·  loaded 4  ·  shown 3  ·  clicked 2
  fill 100%  ·  shown 75%  ·  loads in 0.8s
  2 fast clicks - likely accidental

▾ APP_OPEN  app open
  requested 9  ·  loaded 1  ·  shown 1
  fill 11%  ·  shown 100%  ·  loads in 2.4s
  failed 8  ·  mostly no fill (request OK, but no inventory) [code 3]

▾ Home_NATIVE_AD  native                ● cached 52m · expires in 8m
  requested 6  ·  loaded 5  ·  shown 4
  fill 83%  ·  shown 80%  ·  loads in 1.2s
```

| Number | How it is counted |
|---|---|
| requested, loaded, failed, clicked | One per line of that kind for the slot. The same kind again within 0.5 s is one event logged twice, and counts once |
| shown | Once per appearance: `SHOWN` and the `IMPRESSION` that follows it are the same showing |
| fill | loaded ÷ requested. **Red** under 50%, not counting a request still waiting for its answer |
| shown % | shown ÷ loaded. **Red** under 50%, not counting the ad waiting in cache right now |
| loads in | Average time from `REQUESTED` to `LOADED` |
| cached | Loaded, and not yet shown, expired, removed or failed to show. Banners never are — a loaded banner is already on screen |
| expires | Google's lifetime for a loaded ad: **4 hours** for app open, **1 hour** for interstitial, rewarded and native. Orange in the last 10 minutes, red once past it — an ad shown after that may not earn |

Only lines that name an ad slot are counted; an SDK line that names none cannot be attributed.
The numbers cover the session — since the app started, or since `TOOLS` → `CLEAR` — and, unlike
the log, are never trimmed. Tap a block's title to fold it.

---

## TOOLS

`TOOLS` opens a sheet in the same place as `FILTER` (one closes the other):

```
LOG      MARK  CLEAR  SHARE
SDK      AD INSPECTOR
CONSENT  obtained  ·  can request ads  ·  GDPR applies        RESET
```

| Tool | What it does |
|---|---|
| `MARK` | Adds a numbered divider to the log — "the bug is after mark 3" |
| `CLEAR` | Tap twice: empties the log and the stats and restarts the session, for a clean test run |
| `SHARE` | Opens Android's share sheet with a plain-text report: app and device, the screen, consent, the stats, every log line in history, and the Remote Config. Pastes cleanly into Slack or a ticket. Very long logs keep their newest lines |
| `AD INSPECTOR` | Opens Google's [Ad Inspector](https://developers.google.com/admob/android/ad-inspector). It only opens on a **test device** — register the phone in AdMob or in the app's `RequestConfiguration`. Shown when the app uses Google Mobile Ads |
| `CONSENT` | Live status from Google's User Messaging Platform — `obtained`, `required`, `not required` or `unknown` (the app has not asked yet this session) — whether ads can be requested, and whether GDPR applies. Shown when the app uses UMP |
| `RESET` | Tap twice: clears the stored consent, so the consent form shows again next time the app asks — usually on its next launch |

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
| `fastClickMillis` | `1000` | A click sooner than this after the ad appeared is a `FAST CLICK` |

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

The slot's own name is set aside before the kind is decided, so `Click_PDF_INTER_AD` requesting an
ad is a request, not a click. A line that lists settings — three or more `key=value` or `"key":`
pairs, such as a logged ad config — is shown as `·`, not as an event; Google's error JSON is the
exception and stays `FAILED`. Wrappers that glue text onto a numbered slot (`APP_OPEN_5_error_No`)
or drop the number (`Destroyed APP_OPEN`) are folded back into the one slot.

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

**Using the app's SDKs without depending on them.** Firebase Remote Config, Google Mobile Ads, the
User Messaging Platform and AndroidX Fragment are compile-only dependencies. The library checks for
each in the app at runtime and uses the app's own copy; a feature that needs one — the `FRC` tab,
`AD INSPECTOR`, `CONSENT`, the fragment on the `SCREEN` line — exists only when it is found, and an
app without it gains no dependency.

**Dialogs on the SCREEN line.** Android 10 added a public list of every window in the app
(`WindowInspector`). Naming a dialog's class needs its `Window`, which has no public getter, so the
library reads it through reflection; if an Android version blocks that, the dialog is shown as
`Dialog` with its first line of text.

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

**`STATS` is empty, or a slot is missing.** Only lines that name an ad slot are counted. Check the
`ADS` tab: lines for that slot should show its name.

**A `FAST CLICK` I didn't make.** The gap is measured from the ad's first `SHOWN` or `IMPRESSION`
line. If the app logs "shown" late — after the user already saw the ad — clicks look faster than
they were. Raise `fastClickMillis` if needed.

**"Ad Inspector only opens on a test device".** Add the phone as a test device in the AdMob
console, or register its ID in the app's `RequestConfiguration`, and try again.

**No `SDK` or `CONSENT` row in TOOLS.** The app does not include Google Mobile Ads, or the User
Messaging Platform.

**Consent says `unknown`.** The app has not asked UMP for a consent update in this session yet;
open `TOOLS` again once it has.

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

It needs `gpr.user` and `gpr.key` in `~/.gradle/gradle.properties`, with a
[classic token](#creating-a-classic-token) that has `write:packages` and write access to this
repository. Without them it stops before uploading and says so.

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
