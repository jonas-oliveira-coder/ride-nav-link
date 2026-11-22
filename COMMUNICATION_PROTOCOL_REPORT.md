# BLE GATT Communication Protocol Report — RideNav Link

This document details the Bluetooth Low Energy (BLE GATT) communication architecture and Android integration channels implemented in **RideNav Link** (`RideNavLink.apk`).

---

## 1. Architecture Overview

| Aspect | Specification in **RideNav Link** |
|---|---|
| **Platform / SDK** | Pure Android SDK in Kotlin (`minSdk = 26`, `targetSdk = 34`), zero hybrid frameworks or external libraries |
| **UI & Theming** | Material Design 3 visual system with dynamic color theming (`DynamicColors` / adaptive light & dark schemes) |
| **Turn-by-Turn Navigation** | Google Maps notification capture ([MapsNotificationListener.kt](ride_nav_link/app/src/main/java/com/custom/ridenavlink/MapsNotificationListener.kt)) + dedicated dual-language parser ([GoogleMapsNotificationParser.kt](ride_nav_link/app/src/main/java/com/custom/ridenavlink/GoogleMapsNotificationParser.kt)) + hybrid GPS odometer ([MapsHybridNavigator.kt](ride_nav_link/app/src/main/java/com/custom/ridenavlink/MapsHybridNavigator.kt)) |
| **BLE GATT Link** | [BajajBleManager.kt](ride_nav_link/app/src/main/java/com/custom/ridenavlink/BajajBleManager.kt) + frame encoder [BajajProtocol.kt](ride_nav_link/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt) + foreground service [BajajForegroundService.kt](ride_nav_link/app/src/main/java/com/custom/ridenavlink/BajajForegroundService.kt) |
| **Privacy / Network** | **100% offline & local** — zero cloud analytics, tracking, or remote API calls |

---

## 2. BLE GATT Transport Layer

**RideNav Link** communicates with the motorcycle cluster through **6 custom GATT characteristics**, identified by the UUID suffix `-676e-6972-6565-6e69-676e4543544f`:

| Channel | Full UUID | Direction | Size | Trigger / Frequency | Purpose |
|---|---|---|---|---|---|
| `CHAR_TBT` (`0110`) | `0110676e-6972-6565-6e69-676e4543544f` | Phone → Cluster (Write) | **48 bytes** | Every `1000 ms` during navigation + immediate on distance/maneuver change | Maneuver, distance, roundabout exit, ETD, ETA, street name, and checksum |
| `CHAR_STATUS` (`0210`) | `0210676e-6972-6565-6e69-676e4543544f` | Phone → Cluster (Write) | **55 bytes** | Every `1000 ms` + immediate on call/volume/headset state change | Battery, signal, HFP headset, volume, call state/name, and heartbeat |
| `CHAR_CALLS` (`0310`) | `0310676e-6972-6565-6e69-676e4543544f` | Phone → Cluster (Write) | **74 bytes** | Event-driven | Missed call notification (`RINGING → IDLE`) / persistent notification |
| `CHAR_ALERTS` (`0410`) | `0410676e-6972-6565-6e69-676e4543544f` | Phone → Cluster (Write) | **40 bytes** | Event-driven | Flashing alert for native SMS (`type 1`) or Messaging apps (`type 2`) |
| `CHAR_MEDIA` (`0610`) | `0610676e-6972-6565-6e69-676e4543544f` | Phone → Cluster (Write) | **111 bytes** | Track/state change + `1000 ms` ticker while playing | Song title, album, artist, playback time/duration, `PlayStatus` (`0/1/2`) |
| `CHAR_CONTROLS` (`0A10`) | `0a10676e-6972-6565-6e69-676e4543544f` | Cluster → Phone (Read Polling) | **≥ 14 bytes** (`0..13`) | Polling every `700 ms` | Handlebar switch actions (volume, call, media, Maps, ACKs) |

---

## 3. Channel 1 — Turn-by-Turn Navigation (`0110` — `CHAR_TBT`)

### 3.1 48-Byte Packet Structure

| Byte(s) | Field | Encoding / Logic |
|---|---|---|
| `0` | Main flags + next turn unit + AM/PM | `bit 0 = 1` (active); `bits 4..6 = nextDist[0]` (`1` if `< 1000 m`, `0` if km); `bit 7 = 1` if PM (`!isAm`) |
| `1` | ASCII Maneuver Code | Uppercase (solid) or lowercase (blinking), see table below |
| `2..5` | Next turn distance | Format `[fracLSB, fracMSB, intLSB, intMSB]` |
| `6` | ETA Minutes | `0..59` |
| `7` | Roundabout exit + ETA Hour | `high nibble (bits 4..6) = roundaboutExit (1..7)`; `low nibble (bits 0..3) = hour 1..12` |
| `8..11` | Total remaining distance (ETD) | Format `[fracLSB, fracMSB, intLSB, intMSB]` |
| `12` | ETD unit + validity flag | `(totalDist[0] & 0x0F) \| 4` (`bit 2 = 1` indicates valid ETD) |
| `13` | Reserved | `0` |
| `14` | Street name length | `0..32` bytes |
| `15..46` | Street name (US-ASCII) | Stripped of diacritics, matching `[0-9a-zA-Z.]`, capped at `31 + "."` |
| `47` | **Checksum** | Sum of `bytes[0..46]` masked with `& 0xFF` |

- **Ending navigation:** Sends a 48-byte null array (`0x00`) to clear the cluster display.
- **Blinking threshold (`maneuverForDistance`):**
  - Destination (`'H'`, `72`): switches to `'b'` (`98`) when distance is `< 40 m`.
  - All other blink-capable maneuvers: shift `+32` (uppercase to lowercase ASCII) when distance is `< 100 m`.

---

## 4. Internationalization & Translations

The application follows the official Android localization standard:
- Default language: **English** (`ride_nav_link/app/src/main/res/values/strings.xml`)
- Portuguese language: **Português (Brasil)** (`ride_nav_link/app/src/main/res/values-pt-rBR/strings.xml`)
- Per-App language preferences enabled (`@xml/locales_config`) for Android 13+.
- Translations managed via **Hosted Weblate**.

