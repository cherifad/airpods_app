# Development Plan — Buds Companion

> **Last updated**: November 9, 2026
> **Protocol source of truth**: [`PROTOCOL.md`](PROTOCOL.md)
> **Current state**: mocked Compose UI, bottom-anchored popup, bilingual UI,
> documented RACE protocol, tested frame codec/parser, command queue and
> initialization sequence. The GATT transport and initialization are implemented
> and exposed by a debug diagnostics screen. A debug system-overlay spike (mock
> data) also exists; neither the BLE path nor the overlay is validated on a real
> device, and the production UI is still mocked.

---

## 0. Legend

- `[x]` done
- `[ ]` to do
- `[~]` in progress
- `[!]` blocked
- `[?]` decision needed

Priorities:

- **P0**: required for the MVP.
- **P1**: V1, comfort and polish.
- **P2**: V2, advanced customization.
- **P3**: later / study only.

Complexity: **S** (small), **M** (medium), **L** (large, several days).

**Definition of Done** for a task:

- code builds and is tested;
- unit or instrumentation tests added for non-trivial behavior;
- error and “unknown” states handled;
- light/dark rendering checked when the UI changes;
- documentation updated when a command, convention or architecture changes;
- manual verification described in the PR.

---

## 1. Current state

### 1.1 Done

- [x] Android Gradle project, package `com.airbuds.companion`.
- [x] Material 3 Compose UI, light/dark theme.
- [x] Home screen, hero card, battery, ANC selector.
- [x] Animated in-app popup, scrim, close via X/back/outside tap.
- [x] Popup moved to the bottom, above the navigation bar.
- [x] Mocked state `BudsState` + `BudsStateSaver`.
- [x] Debug build working.
- [x] Manual UI tests on API 30 AVD: auto-show, dismissal, rotation,
  landscape, light/dark theme.
- [x] Bilingual UI: English by default, French through `values-fr`, with
  Android 13+ per-app language support.
- [x] Git repository initialized: `main` for releases, `develop` for integration.
- [x] `PROTOCOL.md`: RACE frame, transport, init, useful payloads, 142 IDs.
- [x] `README.md`: features, build, structure, checks.
- [x] `plan.md`: MVP, V1, V2, milestones, backlog, tests, risks.
- [x] Unit test foundation: JUnit 4 + `kotlinx-coroutines-test`.
- [x] RACE frame model, codec and parser with fragment/concatenation reassembly.
- [x] Serialized command queue with 1 s timeout and 3 attempts.
- [x] BLE permissions declared (scan/connect split on Android 12+, location on 6–11).
- [x] `GattTransport`: filtered scan, connection, service discovery, CCCD
  notifications, MTU 240 negotiation and MTU-sized write chunking.
- [x] Initialization sequence: chip name, device type NVKEY selection, NVDM
  version, TWS role and available destinations/peer.
- [x] Debug diagnostics screen: runtime permissions, scan, connect,
  initialization result and TX/RX RACE log.
- [x] Debug system-overlay spike: `SYSTEM_ALERT_WINDOW`, `connectedDevice`
  foreground service, notification and the Compose popup card via
  `WindowManager` (mock data).

### 1.2 Current limits

- The UI still uses mocked data: no repository or `ViewModel` connects the GATT
  layer to the product screens yet.
- Runtime permissions and device selection exist only in the debug diagnostics
  screen; there is no production onboarding, bonding or reconnection handling.
- The system overlay is a debug spike with mocked data; it still needs hardware
  validation and a production permission/service design. Play policy review is
  required if it ships.
- The GATT transport and initialization sequence are not yet validated on a real
  AB1565 device.
- Unit tests cover the codec/parser/queue/chunking/initializer; the Android GATT
  layer has no instrumentation test yet.
- Case battery is not confirmed on hardware.
- Rename, find my, EQ, touch controls and game mode payloads are not documented.

### 1.3 Technical debt

- `BudsState` mixes UI state and domain data; replace it with an immutable
  state driven by the repository.
- The popup does not reflect the real connection state yet.
- The project has no CI yet; test conventions only cover the RACE unit tests.
- The README still describes a prototype; update it once BLE lands.

---

## 2. Vision and scope

### 2.1 Vision

A **simple, reliable and fast** Android companion app for Airoha earbuds:
connection, battery, ANC and an AirPods-like popup, with a polished Material 3
UI. The app must remain useful when a feature is unavailable on the firmware:
show “unknown” rather than a false value.

### 2.2 MVP (P0)

Goal: replace the mock with a real BLE session on a compatible Airoha device,
with the essential features.

- Scan and connect to the Airoha device.
- Remember the last device and reconnect on launch.
- RACE initialization and capability detection.
- Left/right battery via `0x0CD6`, partner via `0x0D01`.
- Case battery when available, otherwise unknown.
- ANC: Off / Noise Cancelling / Transparency, via `0x0E06` + `0x0901` state.
- In-app popup fed by real state, anchored at the bottom.
- Android 12+ and Android 6–11 permission handling.
- Loading, disconnected, error and unsupported-device states.
- Minimal settings: popup auto-show, forget device.
- Unit tests for the codec and command queue.
- Manual test on a real device.

### 2.3 V1 (P1)

- Device information screen: chip, NVDM version, TWS role, peer presence.
- Device rename, if the payload is found.
- Find my earbuds, if the payload is found.
- Low-battery notification (Android 13+: notification permission).
- Quick Settings tile to open the popup.
- System overlay popup above other apps (`SYSTEM_ALERT_WINDOW` + `connectedDevice`
  foreground service), after the debug spike and Play policy review.
- Multi-device: list of remembered devices.
- Theme setting: system / light / dark.
- FR/EN internationalization.
- Accessibility: TalkBack, touch targets, contrast, large fonts.
- Hidden diagnostics screen: RACE logs, version, last exchange.

### 2.4 V2 / exploratory (P2)

- EQ and presets.
- Touch controls.
- Game mode.
- In-ear detection / auto-pause.
- Home screen widget.
- Material You dynamic theme.
- Firmware update (FOTA): **separate study, high risk**.

### 2.5 Out of scope

- Hex command console for end users.
- NVKEY, flash, RAM, partition or unguarded FOTA writes.
- Cloud account, sync, advertising analytics.
- iOS app.
- Guaranteed support for every Airoha chipset: AB1565/Flycc first.

---

## 3. Target architecture

### 3.1 Overview

```
┌─────────────────────────────────────────────┐
│ Compose UI                                  │
│  HomeScreen / BudsPopup / SettingsScreen    │
└──────────────────┬──────────────────────────┘
                   │ collect StateFlow
┌──────────────────▼──────────────────────────┐
│ BudsViewModel                               │
│  - BudsUiState                              │
│  - actions: connect, refresh, setAnc, ...   │
└──────────────────┬──────────────────────────┘
                   │ suspend / Flow
┌──────────────────▼──────────────────────────┐
│ BudsRepository                              │
│  - orchestration, cache, reconnection       │
│  - device capabilities                      │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│ AirohaBleClient                             │
│  - scan / GATT / permissions                │
│  - RaceCodec / RaceParser                   │
│  - RaceCommandQueue                         │
│  - init, battery, ANC, relay                │
└──────────────────┬──────────────────────────┘
                   │
              BluetoothGatt
```

### 3.2 Proposed tree

```
app/src/main/java/com/airbuds/companion/
├── BudsApplication.kt              # manual DI container
├── MainActivity.kt
├── data/
│   ├── ble/
│   │   ├── AirohaBleClient.kt
│   │   ├── BlePermissions.kt
│   │   ├── GattTransport.kt
│   │   ├── RaceCodec.kt
│   │   ├── RaceFrame.kt
│   │   ├── RaceIds.kt
│   │   ├── RaceParser.kt
│   │   └── RaceCommandQueue.kt
│   ├── repository/
│   │   └── BudsRepository.kt
│   └── preferences/
│       └── BudsPreferences.kt
├── domain/
│   ├── model/
│   │   ├── AncMode.kt
│   │   ├── BudsBattery.kt
│   │   ├── BudsConnection.kt
│   │   └── BudsDeviceInfo.kt
│   └── usecase/                    # only if a use case earns its place
├── ui/
│   ├── AncModeLabels.kt
│   ├── BudsApp.kt
│   ├── BudsUiState.kt
│   ├── BudsViewModel.kt
│   ├── HomeScreen.kt
│   ├── BudsPopup.kt
│   ├── SettingsScreen.kt
│   ├── components/
│   └── theme/
└── debug/
    └── DiagnosticsScreen.kt
```

### 3.3 Architecture decisions

| Topic | Proposed decision | Reason |
|---|---|---|
| BLE | direct Android APIs (`BluetoothGatt`) | custom protocol, no suitable library |
| DI | manual container in `BudsApplication` | small MVP, avoid Hilt for now |
| State | `StateFlow<BudsUiState>` + `ViewModel` | testable, Compose lifecycle |
| Navigation | sealed screen state + `when` for the MVP | few screens, avoid NavHost |
| Persistence | DataStore Preferences | last device, settings, no database |
| Threading | BLE on `Dispatchers.IO`, UI on Main | no I/O on the main thread |
| Commands | serialized queue + timeout + retries | firmware expects one response at a time |
| Errors | sealed `BudsError` model | consistent UI messages |
| Localization | `strings.xml`, English default + `values-fr` | English primary, French secondary |
| Theme | current custom palette | visual identity already defined |
| Background | no persistent connection in the MVP | simplicity and Android restrictions |

### 3.4 Decisions to make

- [?] **Target device**: AB1565 only or several models?
- [?] **Background**: connection only while the app is open, or a foreground
  service in V1?
- [?] **Popup**: auto-show on every connection, or once per session?
- [?] **Case**: show “unknown” when the firmware does not provide battery, or
  hide the row?
- [?] **Multi-device**: V1 or V2?
- [?] **English/French**: English primary and French secondary confirmed; when
  to add more locales?
- [?] **Git/CI**: initialize a remote repository and GitHub Actions?

---

## 4. Milestones

### M0 — Project foundation and quality

Goal: a clean, testable and versioned project before adding BLE.

- [x] Initialize the Git repository and `main` branch.
- [x] Create the `develop` integration branch.
- [x] Create the next work branch (`feat/ble-race`) from `develop`.
- [x] If a remote exists: `git fetch` + `git pull` on `develop` before branching.
- [x] Add test dependencies: JUnit 4 and `kotlinx-coroutines-test` (Turbine and
  Compose UI test will land with their first tests).
- [x] Add a first unit test to validate the setup (`RaceCodecTest`).
- [ ] Add `./gradlew testDebugUnitTest` and `lintDebug` to CI.
- [x] Extract UI strings to `res/values/strings.xml` (English default).
- [x] Add `res/values-fr/strings.xml` and Android 13+ locale config.
- [ ] Add an empty settings screen or a navigation entry.
- [x] Document the build and test commands in the README.

**Acceptance criteria**: debug build OK, unit tests OK, lint OK, no critical
hard-coded UI string.

### M1 — BLE/RACE skeleton

Goal: talk to the firmware and see responses in the logs.

- [x] Implement `RaceFrame`, `RaceCodec`, `RaceParser`.
- [x] Implement `RaceCommandQueue` (one request at a time, 1 s timeout, 3 attempts).
- [x] Implement `GattTransport`: scan, connect, discover, CCCD, MTU.
- [x] Implement the initialization sequence from `PROTOCOL.md` §4.
- [x] Add an in-memory fake transport for tests.
- [x] Unit tests: encoding, chunking, reassembly, timeout, retry.
- [x] Debug diagnostics screen: TX/RX hex, state, errors.
- [ ] Test on a real device: chip name, type, role, destinations.

**Acceptance criteria**: the app connects, initializes and shows the basic
device info; disconnection does not crash; codec tests pass.

### M2 — Connection, battery and real state

Goal: replace the mocked battery and connection state.

- [ ] Models `BudsConnection`, `BudsBattery`, `BudsDeviceInfo`.
- [ ] `BudsRepository`: connection → init → ready → disconnect cycle.
- [ ] `BudsViewModel` + `BudsUiState`.
- [ ] Local battery `0x0CD6`.
- [ ] Partner battery through relay `0x0D01` when a peer is present.
- [ ] Refresh policy: on connection, every 30 s, on return to foreground.
- [ ] Automatic reconnection to the last device.
- [ ] Error handling: Bluetooth off, permission denied, timeout, missing device.
- [ ] Bind the home screen to the `ViewModel`; progressively remove the mock.
- [ ] Case battery via `0x2100/0x0D` when available, otherwise unknown.
- [ ] Tests: battery parsing, relay, state mapping.

**Acceptance criteria**: real battery values displayed, consistent with the
vendor app, reconnection working, error states visible.

### M3 — Real ANC

Goal: control ANC and reflect the firmware state.

- [ ] Read state via `0x0901` module 5.
- [ ] Enable via `0x0E06` (`00 0A <filter> [mode] 01`).
- [ ] Disable via `0x0E06` (`00 0B 01`).
- [ ] Set pass-through gain when needed (`00 0F <gain> 01`).
- [ ] Map the 3 UI modes:
  - Off → `filter = 0`;
  - Noise Cancelling → `filter = 1..4`;
  - Transparency → `filter = 9..11`, `mode = 4`.
- [ ] Optimistic update + rollback when the firmware rejects it.
- [ ] Verify synchronization after a change from another device.
- [ ] Unit tests for the mapping and payloads.

**Acceptance criteria**: the 3 modes work on a real device, the UI state stays
in sync, and no crash occurs when a command fails.

### M4 — Real-time popup and settings

Goal: the complete MVP user experience.

- [ ] Popup fed by `BudsUiState` (name, connection, battery, ANC).
- [ ] Popup anchored at the bottom, navigation-bar margin, landscape scroll.
- [x] Debug system-overlay spike: floating Compose card, overlay permission,
  foreground service and notification, with mock data.
- [ ] Production system overlay: real state, auto-show on connection, permission
  onboarding and Play policy validation.
- [ ] Auto-show policy: first connection of the session, then manual.
- [ ] Close via X / back / scrim.
- [ ] Settings screen: auto-show, forget device, diagnostics.
- [ ] Permission flow with rationale before the request.
- [ ] Scan screen: device list, RSSI, connection state.
- [ ] States: loading, disconnected, unsupported device, error.
- [ ] Accessibility: descriptions, touch targets, focus order.
- [ ] Check light/dark, portrait/landscape, large fonts.

**Acceptance criteria**: complete user journey without mock data, popup correct
at the bottom, permissions understood, settings persistent.

### M5 — V1

Goal: comfort and differentiating features.

- [ ] Detailed device information.
- [ ] Rename (if payload found).
- [ ] Find my earbuds (if payload found).
- [ ] Low-battery notification.
- [ ] Quick Settings tile.
- [ ] Multi-device.
- [x] FR/EN (English primary, French secondary).
- [ ] Diagnostics screen reachable through a hidden gesture or a debug build.

**Acceptance criteria**: each feature has a documented manual test and a
degraded behavior when the firmware does not support it.

### M6 — Release

Goal: publish a clean internal beta.

- [ ] Release signing configuration (keystore outside the repository).
- [ ] R8/ProGuard and keep rules.
- [ ] Baseline profile if time allows.
- [ ] Privacy policy.
- [ ] Store listing: description, screenshots, icon, banner.
- [ ] Internal test track in Play Console.
- [ ] Regression checklist on a real device.
- [ ] Version tag and release notes.

**Acceptance criteria**: signed AAB, installable internal test, no known P0 bug,
documented rollback procedure.

---

## 5. Detailed backlog

### 5.1 Project and tooling

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| P-01 | Initialize Git, `main` and `develop` branches | P0 | S | — |
| P-02 | Add test dependencies | P0 | S | P-01 |
| P-03 | Configure CI build + tests + lint | P1 | M | P-02 |
| P-04 | Extract strings to `strings.xml` | P0 | M | — |
| P-05 | Document branch and commit conventions in `AGENTS.md` | P0 | S | P-01 |
| P-06 | Add a PR template with a manual checklist | P1 | S | P-01 |
| P-07 | Add `CHANGELOG.md` | P2 | S | — |
| P-08 | Verify build reproducibility (wrapper, JDK) | P1 | S | — |
| P-09 | Add `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` and DataStore Preferences | P0 | S | — |

### 5.2 Architecture and data

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| A-01 | Create `BudsApplication` + manual DI container | P0 | S | — |
| A-02 | Create `BudsUiState` and `BudsViewModel` | P0 | M | A-01 |
| A-03 | Create `BudsRepository` | P0 | M | A-01 |
| A-04 | Create `BudsConnection`, `BudsBattery`, `BudsDeviceInfo` models | P0 | S | — |
| A-05 | Create `BudsPreferences` (DataStore) | P0 | S | A-01 |
| A-06 | Create a `BudsError` error model | P0 | S | — |
| A-07 | Define the connection state machine | P0 | M | A-02 |
| A-08 | Define the battery refresh policy | P0 | S | A-03 |
| A-09 | Define the reconnection policy | P0 | M | A-03 |
| A-10 | Remove the mock from the release build | P0 | S | A-02 |
| A-11 | Declare `BudsApplication` in the manifest | P0 | S | A-01 |
| A-12 | Lifecycle: pause/resume and BLE job cancellation | P0 | M | A-03 |

### 5.3 BLE and RACE

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| B-01 | Android 12+ / 6–11 permissions | P0 | M | — |
| B-02 | Scan filtered by the Airoha service | P0 | M | B-01 |
| B-03 | GATT connection and service discovery | P0 | M | B-02 |
| B-04 | CCCD activation + MTU 240 request | P0 | M | B-03 |
| B-05 | `RaceFrame` + `RaceCodec` (encode/decode) | P0 | S | — |
| B-06 | `RaceParser` with reassembly | P0 | M | B-05 |
| B-07 | `RaceCommandQueue` (timeout, retry, matching) | P0 | M | B-05 |
| B-08 | Initialization sequence | P0 | M | B-04, B-07 |
| B-09 | Agent battery `0x0CD6` | P0 | S | B-08 |
| B-10 | Partner battery `0x0D01` | P0 | M | B-09 |
| B-11 | ANC read `0x0901` | P0 | S | B-08 |
| B-12 | ANC write `0x0E06` | P0 | M | B-11 |
| B-13 | Case battery `0x2100/0x0D` (YFY firmware) | P0 | M | B-08 |
| B-14 | Reconnection and re-init | P0 | M | B-08 |
| B-15 | RACE diagnostics screen | P0 | S | B-05 |
| B-16 | Fake transport for tests | P0 | M | B-05 |
| B-17 | Malformed-frame handling | P0 | S | B-06 |
| B-18 | Hex logs with MAC redaction | P1 | S | B-15 |
| B-19 | Background scan / foreground service (if approved) | P1 | L | B-03 |
| B-20 | Multi-device: address cache | P1 | M | B-08 |
| B-21 | Handle pairing/bonding when required | P1 | M | B-03 |
| B-22 | Handle `0x5D` indications and `0x2100/0x0D` | P0 | M | B-08 |

### 5.4 UI/UX

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| U-01 | Bind HomeScreen to `BudsUiState` | P0 | M | A-02 |
| U-02 | Bind BudsPopup to `BudsUiState` | P0 | S | U-01 |
| U-03 | Scan / device selection screen | P0 | M | B-02 |
| U-04 | Permission flow with rationale | P0 | M | B-01 |
| U-05 | Loading / empty / error states | P0 | M | A-06 |
| U-06 | Settings screen (auto-show, forget, diagnostics) | P0 | M | A-05 |
| U-07 | Popup auto-show policy | P0 | S | U-02 |
| U-08 | TalkBack accessibility + touch targets | P0 | M | U-01 |
| U-09 | Large font / display size | P0 | S | U-01 |
| U-10 | English/French translations | P1 | M | P-04 |
| U-11 | Device information screen | P1 | S | A-04 |
| U-12 | Quick Settings tile | P1 | M | U-02 |
| U-13 | Home screen widget | P2 | L | U-02 |
| U-14 | Material You dynamic theme | P2 | S | — |
| U-15 | Store screenshots + banner | P3 | M | M6 |
| U-16 | Theme setting: system / light / dark | P1 | S | A-05 |
| U-17 | App icon, splash screen, app name | P1 | S | — |
| U-18 | First-launch onboarding | P1 | M | U-04 |
| U-19 | Debug system-overlay spike (`SYSTEM_ALERT_WINDOW` + FGS + Compose) | P1 | M | — |
| U-20 | Production system overlay with real state and auto-show | P1 | L | U-19, A-03 |

### 5.5 V1/V2 features

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| F-01 | Rename device (RE payload) | P1 | L | B-08 |
| F-02 | Find my earbuds (RE payload) | P1 | L | B-08 |
| F-03 | Low-battery notification | P1 | M | U-01, B-09 |
| F-04 | Multi-device | P1 | L | B-20 |
| F-05 | EQ / presets (RE) | P2 | L | B-08 |
| F-06 | Touch controls (RE) | P2 | L | B-08 |
| F-07 | Game mode (RE) | P2 | M | B-08 |
| F-08 | In-ear detection / auto-pause (RE) | P2 | L | B-08 |
| F-09 | Firmware update (FOTA) | P3 | L | dedicated study |

### 5.6 Tests and quality

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| Q-01 | `RaceCodec` unit tests | P0 | S | B-05 |
| Q-02 | `RaceParser` tests (fragments, concatenation) | P0 | M | B-06 |
| Q-03 | `RaceCommandQueue` tests (timeout, retry) | P0 | M | B-07 |
| Q-04 | Repository tests with fake transport | P0 | M | B-16 |
| Q-05 | ANC mapping tests | P0 | S | B-12 |
| Q-06 | Compose tests for popup and settings | P1 | M | U-06 |
| Q-07 | Manual test matrix | P0 | M | M2 |
| Q-08 | Reconnection / Bluetooth loss test | P0 | M | B-14 |
| Q-09 | Rotation / process death test | P0 | S | U-01 |
| Q-10 | Performance: no I/O on the main thread | P1 | S | B-03 |
| Q-11 | Accessibility audit | P1 | M | U-08 |
| Q-12 | Real AB1565 device test | P0 | L | M2 |
| Q-13 | API 33+ test (notification permissions) | P1 | M | F-03 |

### 5.7 Security and privacy

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| S-01 | No secrets in the repository | P0 | S | P-01 |
| S-02 | Validate every frame length | P0 | S | B-06 |
| S-03 | No destructive command in the UI | P0 | S | B-07 |
| S-04 | Redact the MAC address in exported logs | P1 | S | B-18 |
| S-05 | Privacy policy | P1 | M | M6 |
| S-06 | Verify manifest permissions | P0 | S | B-01 |
| S-07 | No analytics by default | P0 | S | — |

### 5.8 Documentation

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| D-01 | Maintain `PROTOCOL.md` with validated payloads | P0 | M | B-08 |
| D-02 | Add `docs/architecture.md` | P1 | M | A-03 |
| D-03 | Add `docs/testing.md` | P1 | S | Q-07 |
| D-04 | Update the README at every milestone | P0 | S | — |
| D-05 | Document firmware limitations | P1 | S | Q-12 |
| D-06 | Add a contribution guide | P2 | S | P-05 |

### 5.9 Release

| ID | Task | Prio | Complexity | Depends on |
|---|---|---|---|---|
| R-01 | Release signing | P0 | M | M6 |
| R-02 | R8/ProGuard | P1 | M | R-01 |
| R-03 | Baseline profile | P2 | M | R-01 |
| R-04 | Play Store listing | P1 | M | R-01 |
| R-05 | Internal test | P1 | M | R-01 |
| R-06 | Versioning and tags | P1 | S | P-01 |
| R-07 | Rollback procedure | P2 | S | R-05 |

---

## 6. Reverse-engineering backlog

Every unknown feature must be **timeboxed** and validated on hardware. Methods:
Android HCI snoop log, Frida on `RacePacket`/`AirohaMmiMgr`, comparison with
Flycc, minimal frame tests.

| Feature | ID / channel | Unknown | Method | Prio | Timebox |
|---|---|---|---|---|---|
| Case battery | `0x2100/0x0D` | availability per firmware | notification + test | P0 | 1 day |
| Standard case battery | `0x000B`, `0x0009` | exact payload | HCI snoop / test | P2 | 1 day |
| Rename | to find | ID + payload + validation | Frida + capture | P1 | 2 days |
| Find my | `0x2C00`/`0x2C01` | start/stop/state payload | Flycc capture | P1 | 2 days |
| EQ | SDK EQ IDs | preset/band payload | capture + test | P2 | 3 days |
| Touch controls | SDK IDs | action/key mapping | capture + test | P2 | 3 days |
| Game mode | `0x2100` event 7 | set command | capture + test | P2 | 1 day |
| In-ear detection | SDK IDs | payload | capture + test | P2 | 2 days |
| Firmware info | NVKEY / FOTA | version, build | NVKEY read | P2 | 1 day |

Rule: if a feature is not validated within its timebox, it stays “unknown” in
the UI and is not exposed.

---

## 7. Manual test matrix

| Scenario | API 30 | API 33+ | Real AB1565 device |
|---|---|---|---|
| First launch + permissions | [ ] | [ ] | [ ] |
| Permission denied, then retry | [ ] | [ ] | [ ] |
| Bluetooth disabled | [ ] | [ ] | [ ] |
| Scan and connection | [ ] | [ ] | [ ] |
| Several Airoha devices in range | [ ] | [ ] | [ ] |
| Device already connected to another phone | [ ] | [ ] | [ ] |
| Full initialization | [ ] | [ ] | [ ] |
| Left/right battery | [ ] | [ ] | [ ] |
| Case battery (when available) | [ ] | [ ] | [ ] |
| ANC 3 modes | [ ] | [ ] | [ ] |
| ANC change from the app | [ ] | [ ] | [ ] |
| Disconnect / reconnect | [ ] | [ ] | [ ] |
| Phone reboot, then reconnect | [ ] | [ ] | [ ] |
| Bluetooth loss during a command | [ ] | [ ] | [ ] |
| Portrait/landscape rotation | [ ] | [ ] | [ ] |
| Process death | [ ] | [ ] | [ ] |
| Light/dark theme | [ ] | [ ] | [ ] |
| Edge-to-edge / system bars | [ ] | [ ] | [ ] |
| Large font | [ ] | [ ] | [ ] |
| TalkBack | [ ] | [ ] | [ ] |
| Low battery | [ ] | [ ] | [ ] |
| App in background, then return | [ ] | [ ] | [ ] |

---

## 8. Risks and open questions

| Risk | Impact | Mitigation | Decision |
|---|---|---|---|
| No real device available | validation impossible | buy/borrow an AB1565, otherwise BLE mock | ? |
| Firmware differs from analyzed Flycc | missing commands | capability detection + unknown states | ? |
| Case battery not exposed | empty row | show “unknown” or hide | ? |
| Android background restrictions | limited reconnection | MVP app-open only, foreground service in V1 | ? |
| Unknown payloads (rename, find my) | V1 features blocked | timebox RE, otherwise defer | ? |
| Popup is not system-wide | user expectation | explain the limitation in the UI/README | — |
| BLE unreliable on some phones | connection failures | timeout, retry, logs, test matrix | — |
| Android 12+ permissions | scan impossible | rationale + clear flow | — |
| R8 breaks BLE callbacks | release crash | release tests + keep rules | — |
| MAC address leak in logs | privacy | redaction | — |
| Unguarded FOTA | bricked device | out of scope | — |
| Play Console account/signing unavailable | release blocked | create account early, internal test | ? |

---

## 9. Recommended execution order

1. **M0**: Git, tests, strings, minimal CI.
2. **M1**: codec + parser + queue + GATT + init, validated on a real device.
3. **M2**: repository + ViewModel + battery + reconnection.
4. **M3**: ANC + mapping + state.
5. **M4**: real-time popup + settings + permissions + accessibility.
6. **M5**: V1, starting with features whose payload is known.
7. **M6**: internal release.

Do not start V1 before the MVP is stable on a real device.

---

## 10. PR checklist

- [ ] The feature is covered by a test or a manual check.
- [ ] Error and “unknown” states are handled.
- [ ] No destructive command was added.
- [ ] Strings are in `strings.xml` (English default + French).
- [ ] Light/dark rendering was checked if the UI changed.
- [ ] Landscape and large fonts were checked if the UI changed.
- [ ] Logs contain no secret or cleartext MAC address.
- [ ] `PROTOCOL.md`/README were updated when needed.
- [ ] The `Manually verified:` section of the PR describes the real tests.

---

## 11. Immediate next action

**M1 (continued)**: validate the GATT + initialization path and the debug system
overlay on a real AB1565. Then start M2: repository/`ViewModel`, battery
`0x0CD6`, partner relay `0x0D01` and real product UI wiring. The production
overlay and permission flow come with M4; M0 leftovers (CI and the empty
settings screen) can follow.


