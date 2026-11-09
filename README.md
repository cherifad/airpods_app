# Buds Companion

> **Language:** English. The app UI is also available in French.

Android companion app built with **Jetpack Compose / Material 3** for Airoha/Flycc
earbuds. The UI is a polished prototype: a home screen and an **AirPods-inspired
connection popup** with left/right/case battery levels and ANC control.

The data is still **mocked** (`BudsState`); the real BLE/RACE layer is the next
milestone. The protocol is documented in [`PROTOCOL.md`](PROTOCOL.md), and the
full roadmap is in [`plan.md`](plan.md).

| Light mode | Dark mode |
|---|---|
| ![Light popup](docs/screenshots/popup-light.png) | ![Dark popup](docs/screenshots/popup-dark.png) |
| ![Light home](docs/screenshots/home-light.png) | ![Dark home](docs/screenshots/home-dark.png) |

## Features

- Bottom-anchored floating popup, 32 dp corners, shadow and subtle border.
- Spring entry from the bottom with a fading scrim.
- Background blur via `Modifier.blur` on Android 12+; scrim-only fallback on API 26–30.
- iOS-like battery glyphs with animated fill, green/amber/red depending on level.
- Segmented ANC control (Noise Cancelling / Transparency / Off), shared with the home screen.
- Material 3 light/dark theme, edge-to-edge, custom typography and shapes.
- Popup shown automatically 700 ms after launch; reopened from the hero card or the button.
- Dismissed with the close button, the system back gesture or a tap outside.
- Bilingual UI: **English by default**, **French** through `values-fr`, with Android 13+
  per-app language support.

## Build

Requirements: JDK 17+ (tested with JDK 21), Android SDK 36 and build-tools 36.0.0.

```bash
# Create this file if missing, using your local SDK path
echo "sdk.dir=/opt/android-sdk" > local.properties

./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Structure

```
app/src/main/java/com/airbuds/companion/
├── MainActivity.kt              # edge-to-edge + theme
├── model/
│   ├── AncMode.kt               # Noise Cancelling / Transparency / Off
│   └── BudsState.kt             # mocked state (name, battery, ANC, connection)
└── ui/
    ├── AncModeLabels.kt         # localized ANC label mapping
    ├── BudsApp.kt               # shared state, auto-show, blur, BackHandler
    ├── HomeScreen.kt            # header, hero card, controls, battery
    ├── BudsPopup.kt             # bottom popup + scrim + animations
    ├── components/
    │   ├── AncSelector.kt       # custom segmented control
    │   ├── Battery.kt           # BatteryPill + BatteryRow
    │   └── BudsIllustration.kt  # Canvas illustration, no image assets
    └── theme/                   # Color, Type, Theme

app/src/main/res/
├── values/strings.xml           # English (default)
├── values-fr/strings.xml        # French
└── xml/locales_config.xml       # per-app language (Android 13+)
```

## Manual checks (AVD API 30, 1080×2340)

- [x] Popup anchored at the bottom, above the navigation bar, entering from the bottom.
- [x] Popup shown automatically at launch, then dismissible with the close button,
      back or a scrim tap.
- [x] Reopened from the hero card and the button.
- [x] ANC change in the popup reflected immediately on the home screen.
- [x] State (battery, ANC, popup visibility) preserved across rotation.
- [x] Light and dark rendering.
- [x] Scrollable popup in landscape without clipping.
- [x] English UI by default and French UI through the system locale.
- [ ] Background blur to verify on an API 31+ device (not active on the API 30 AVD).

## Next steps

1. **BLE/RACE**: Airoha GATT scan, service
   `5052494D-2DAB-0341-6972-6F6861424C45`, TX/RX `43484152-…-3241-…` / `…-3141-…`.
2. Initialization: NVKEY 4098 → 4097, device type, role `0x0CC4`,
   available destinations `0x0D00`.
3. Bind the UI to real commands: battery `0x0CD6`, ANC `0x0E06` + state `0x0901`,
   TWS relay `0x0D01`.
4. Replace `BudsState` with a `ViewModel` + repository, including notification handling.

See [`plan.md`](plan.md) for the complete milestone breakdown.

## Notes

- The popup is **in-app**, not a system overlay: Android cannot exactly replicate the
  iOS system popup above other apps without a service and `SYSTEM_ALERT_WINDOW`.
- Screenshots were captured on the `flycc` AVD (API 30, 1080×2340).
- English is the primary project language; the app UI also ships a French localization.
