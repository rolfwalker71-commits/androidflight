# FlightBuddy (Android)

Standalone Android-App für Live-Flugtracking. **Kein Server, kein Konto, keine Share-Links.** Die App spricht AeroDataBox, OpenSky und FlightRadar24 direkt an.

Standalone Android app for live flight tracking. **No FlightBuddy server, no accounts, no share links.** The app talks to AeroDataBox / OpenSky / FR24 itself.

Display name: **FlightBuddy** · `applicationId`: `de.rolfwalker.flightbuddy` · Sideload only (not Play Store).

## Deutsch

### API-Schlüssel

Das GitHub-Repo ist öffentlich. **Niemals echte Schlüssel committen.**

1. Kopiere `secrets.properties.example` nach `local.properties` (liegt bereits gitignored) und trage ein:
   - `OPENSKY_USERNAME` / `OPENSKY_PASSWORD` (OpenSky OAuth2 Client-ID / Secret)
   - `AERODATABOX_KEY` und `AERODATABOX_HOST` (oder `AERODATABOX_BASE_URL`)
   - `FR24_API_TOKEN`, `FR24_ENABLED`, `FR24_MIN_INTERVAL_MS`
   - `OPENSKY_MIN_INTERVAL_MS`
2. Debug-Builds lesen diese Werte und legen sie beim ersten Start in **EncryptedSharedPreferences** ab.
3. In der App unter **Einstellungen** kannst du alle Schlüssel sehen und ändern. FR24 lässt sich dort pausieren.

Ohne AeroDataBox-Schlüssel funktioniert die offizielle Flugsuche nicht (manuelle Eingabe bleibt möglich).

### JDK und Gradle-Sync

Bytecode-Ziel ist **Java 17**. Gradle selbst kann auf **JBR 21** laufen. Für die AGP-Toolchain liegt zusätzlich **Temurin 17** unter `C:\Users\rolf\.jdks\jdk-17` (Gradle findet das per Auto-Detect in `~\.jdks`).

In `local.properties` muss `sdk.dir` auf das Android SDK zeigen. Optional: `org.gradle.java.home` in der gitignored `gradle-local.properties` (Vorlage: `gradle-local.properties.example`).

**Android Studio auf Windows:** Gradle-JDK darf **jbr-21** bleiben (`C:\Users\rolf\.jdks\jbr-21.0.11`). Toolchain 17 ist jetzt lokal installiert.

1. **File → Sync Project with Gradle Files** (Elefant).
2. Nur falls der Sync noch den alten Daemon nutzt: Gradle-Tool-Fenster → Stopp, dann nochmal Sync. **File → Invalidate Caches** nur als letzte Reserve.

**Windows-Daemon stoppen** (Android Studio hält oft einen eigenen, unabhängig von WSL):

1. **Android Studio:** Gradle-Tool-Fenster → Stopp (Elefant). **File → Settings allein reicht nicht.**
2. Oder im Projektordner in **cmd.exe** (nicht WSL), nachdem `gradlew.bat` Java findet: `gradlew.bat --stop`
3. Ohne funktionierenden Wrapper: Task-Manager → Prozesse „OpenJDK“ / Gradle-Daemons beenden. Oder im **Studio-Terminal** (wenn *dort* Java existiert): `gradlew.bat --stop`

`java.exe --stop` allein stoppt Gradle nicht. Unter WSL: `./gradlew --stop` — das beendet nur den Linux-Daemon.

Das gebündelte Studio-JBR (`C:\Program Files\Android\Android Studio\jbr`) ist derzeit **Java 25** — für Gradle 8.11 unsicher. Dann JBR 21 oder Temurin 21 als Gradle JDK wählen.

### APK bauen

Voraussetzungen: JDK 21+ (z. B. JBR 21), Android SDK 35, `sdk.dir` in `local.properties`.

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions erzeugt dasselbe Debug-APK als Artifact (`FlightBuddy-debug`). Release-Signing ist nicht eingerichtet — für Sideload reicht Debug.

### Hintergrund-Tracking

Sobald mindestens ein kommender/live Flug (oder ein gespeichertes Objekt) existiert, läuft ein **Foreground-Service** mit der ehrlichen Notification *„FlightBuddy überwacht deine Flüge“*.

Der Service pollt nach dem PWA-Takt (weit weg: Stunden; Preflight/Live: Minuten bzw. 10s in der heißen Phase), respektiert aber Provider-Minima (OpenSky ~90s, FR24 ≥3 min). Jeder erfolgreiche Poll schreibt Room, aktualisiert **Widgets** und erzeugt bei Delay / Diversion / Cancel / Gate / Squawk 7500–7700 / Erinnerungen eine Meldung im Posteingang **und** eine lokale Notification.

WorkManager ist nur der Watchdog (Boot, App-Update, 15-Minuten-Check). Unter Einstellungen: Akku-Optimierung ausnehmen.

### Widgets

Resizeable Glance-Widget (praktisch 2×1 bis 5×4: 2×1, 2×2, 3×2, 4×1, 4×2, 4×3, 4×4, 5×2, 5×3). Beim Anheften einen konkreten Flug wählen. Jede Größe zeigt Airline-Logo, Nummer, Route, Status und so viele Hinweise wie Platz ist (Delay, Diversion, 7700). Tippen öffnet die Flugdetailansicht.

### Hinweis

Airline-Logos: [pics.avs.io](https://pics.avs.io) (Travelpayouts) — keine offiziellen Marken. Kartentiles: Carto / OSM / Esri / OpenTopo. Fotos: Planespotters.net. API-Nutzung unterliegt den jeweiligen ToS (AeroDataBox API.Market, OpenSky, FR24 official API — kein Scraping von flightradar24.com).

## English

### API keys

This GitHub repo is **public**. Never commit secret values.

Seed keys into gitignored `local.properties` (see `secrets.properties.example`). Debug builds copy them into EncryptedSharedPreferences on first launch. Settings shows and edits OpenSky, `AERODATABOX_KEY`, `AERODATABOX_HOST`, `FR24_API_TOKEN`, the FR24 pause switch, and interval overrides.

### JDK and Gradle sync

Bytecode target is **Java 17**. Gradle itself can run on **JBR 21**. A Windows Temurin 17 is installed at `C:\Users\rolf\.jdks\jdk-17` so AGP toolchain lookup succeeds (Studio Gradle JDK can stay jbr-21).

Set `sdk.dir` in `local.properties` to your Android SDK. Optional: `org.gradle.java.home` in gitignored `gradle-local.properties` (see `gradle-local.properties.example`).

**Android Studio / Cursor on Windows** runs Gradle as a Windows process — a JDK that exists only in WSL does not count.

1. **File → Settings** (or **Ctrl+Alt+S**)
2. **Build, Execution, Deployment → Build Tools → Gradle**
3. Set **Gradle JDK** to **jbr-21** (e.g. `C:\Users\rolf\.jdks\jbr-21.0.11`)
4. **Apply → OK**
5. **File → Sync Project with Gradle Files** (or the elephant icon)

After toolchain / `settings.gradle.kts` changes: **File → Invalidate Caches is not required.** Stop daemons so the next sync is fresh.

**Stop the Windows daemon** (Android Studio often keeps one separate from WSL):

1. **Android Studio:** Gradle tool window → stop (elephant). **File → Settings alone is not enough.**
2. Or from the project folder in **cmd.exe** (not WSL), after `gradlew.bat` can find Java: `gradlew.bat --stop`
3. Without a working wrapper: Task Manager → end “OpenJDK” / Gradle daemon processes. Or from the **Studio terminal** (if *that* terminal has Java): `gradlew.bat --stop`

`java.exe --stop` does not stop Gradle. From WSL: `./gradlew --stop` — that only stops the Linux daemon.

Android Studio’s bundled JBR (`C:\Program Files\Android\Android Studio\jbr`) is currently **Java 25**, which Gradle 8.11 may reject. Use JBR 21 or Temurin 21 as the Gradle JDK.

### Build the APK

JDK 21+ (e.g. JBR 21) + Android SDK 35 + `sdk.dir` in `local.properties`:

```bash
./gradlew :app:assembleDebug
```

CI uploads the debug APK as a sideload artifact.

### Background tracking

A long-running **foreground service** stays up whenever a flight is upcoming/live. It is the source of truth for polls, Room, widgets, and alerts (delays, diversions, cancellations, gate changes, squawk 7500/7600/7700, preflight / gate-close / arrival-soon). WorkManager only restarts the service after reboot or if it was killed.

### Widgets

Pin a specific tracked flight. Every size includes the airline logo. Widgets refresh on every successful poll of that flight, not only every 30 minutes.
