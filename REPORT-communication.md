# Flycc (com.airoha.twshelper) — Earbud Communication Analysis

> **Language:** English.

Analyzed APK: `rea/Flycc.ver.2.0.35.build.2023052214.apk`
SHA-256: `2f00a9db722da4fa787be12b63a91c30f7d067f76f95664d3787ce6228323ef1`
Target: Android, minSdk 21, targetSdk 33, package `com.airoha.twshelper`.

## 1. Summary

The app is built on the Airoha SDK (AB155x/AB1562/AB1568/AB157x chipsets). It
talks to the earbuds with the proprietary **RACE** protocol, in clear, without
encryption or application authentication, over two possible transports:

- **BLE GATT** (default): Airoha proprietary service/characteristics.
- **Bluetooth Classic RFCOMM/SPP** (“engineer”/legacy mode).

A frame is a 6-byte header plus payload. Commands range from battery reads to
flash/RAM read-write (FOTA, NVKEY, ANC dump), which overlaps the Airoha
CVE-2025-20700/20701/20702 area.

## 2. Method

The APK is protected by the **Baidu packer**:
`android:name="com.sagittarius.v6.StubApplication"`, and the business code is
encrypted in `assets/baiduprotect1.jar` / `baiduprotect2.jar` (magic
`31 70 6c 21`). The shipped `classes.dex` contains only 2,161 support classes
(androidx, gson, baidu, stub).

Unpacking steps:

1. Android 11 AOSP x86_64 emulator (`avd: flycc`), `adb root`.
2. `frida-server` 17.26.2 pushed to `/data/local/tmp`.
3. `noop_baidu_map.js`: neutralizes `SDKInitializer.initialize` (the Baidu Maps
   SDK has no x86_64 JNI and killed the process before analysis).
4. `frida-dexdump` on the live process → 39 dex files, including
   `classes05.dex` (the Airoha SDK).
5. `jadx` on `classes05.dex` + `classes06.dex`.

Decompiled sources: `/tmp/opencode/flycc/jadx-sdk/sources`.

## 3. Communication stack

```
UI (com.airoha.utapp.sdk, com.airoha.yfy)
        │
com.airoha.sdk (AirohaSDK, AirohaConnector, AB1562/1568/158x/155xDeviceControl)
        │
com.airoha.libbase.RaceCommand  ← RACE frame + RaceId (142 commands)
        │
com.airoha.liblinker
  ├─ host/GeneralHost, TxScheduler      ← TX/RX queues, timeouts, retries
  ├─ transport/Bypass | Nmea | H4       ← optional encapsulation
  └─ physical/gatt | physical/spp       ← Android BluetoothGatt / BluetoothSocket
```

Key classes:

| Role | File |
|---|---|
| SDK entry point | `com/airoha/sdk/AirohaSDK.java` |
| Connection | `com/airoha/sdk/AirohaConnector.java` |
| UUIDs | `com/airoha/liblinker/constant/UuidTable.java` |
| Frame | `com/airoha/libbase/RaceCommand/packet/RacePacket.java` |
| Command IDs | `com/airoha/libbase/RaceCommand/constant/RaceId.java` |
| GATT | `com/airoha/liblinker/physical/gatt/GattController.java` |
| SPP | `com/airoha/liblinker/physical/spp/SppController.java` |

## 4. Discovery and connection

- 10-second BLE scan (`BluetoothAdapter.startLeScan`, limited to 3 scans per
  30 s): `MenuFragment.scanLeDevice`.
- Already-paired Classic devices are also listed; the app checks the Airoha SDP
  service UUID (`MenuFragment.checkAirohaDevice`).
- The transport is selected by preferred protocol (`ConnectionProtocol`):
  `PROTOCOL_BLE = 1`, `PROTOCOL_SPP = 16`. The app default is `PROTOCOL_SPP`,
  with a BLE switch when the device is advertised over LE.

### UUIDs (UuidTable)

| Purpose | UUID |
|---|---|
| Airoha GATT service | `5052494D-2DAB-0341-6972-6F6861424C45` |
| GATT TX (write) | `43484152-2DAB-3241-6972-6F6861424C45` |
| GATT RX (notification) | `43484152-2DAB-3141-6972-6F6861424C45` |
| CCCD descriptor | `00002902-0000-1000-8000-00805F9B34FB` |
| Airoha SPP | `00000000-0000-0000-0099-AABBCCDDEEFF` |
| Standard SPP | `00001101-0000-1000-8000-00805F9B34FB` |
| Alternative “TRSPX” service | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` |
| TRSPX TX / RX | `49535343-8841-43F4-A8D4-ECBE34729BB3` / `49535343-1E4D-4BD9-BA61-23C647249616` |

GATT: requested MTU 240, transport `AUTO`/`LE`/`BREDR`
(`GattLinkParam.TransportEnum`).
SPP: `createRfcommSocketToServiceRecord(AirohaSPP UUID)`.

Encapsulation (`AbstractHost.changeTransport`):

- `BypassTransport`: raw RACE bytes.
- `NmeaTransport`: ASCII/NMEA frames.
- `H4Transport`: HCI H4 type at the head (`0x04` event, `0x02` ACL,
  `0x05`/`0x15` RACE). Despite its name, it does not add a header to RACE
  payloads; over GATT, RACE bytes remain raw.

## 5. RACE frame

`RacePacket.getRaw()` produces exactly:

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 1 | Head | `0x05` command, `0x15` FOTA |
| 1 | 1 | Type | `0x5A` request, `0x5B` response, `0x5C` no-response, `0x5D` indication |
| 2 | 2 | Length (LE) | size = 2 (ID) + payload |
| 4 | 2 | Cmd ID (LE) | RACE identifier |
| 6 | n | Payload | command-specific |

> An earlier revision of this report listed `0x07` as a RACE head. It is an HCI
> event type in the H4 transport, not an application RACE header; `PROTOCOL.md`
> documents the corrected framing.

Constants: `RaceType.CMD_NEED_RESP = 90 (0x5A)`, `RESPONSE = 91 (0x5B)`,
`CMD_NO_RESP = 92 (0x5C)`, `INDICATION = 93 (0x5D)`.
The response payload normally starts with a status byte (`mStatus = bArr[6]`),
followed by a 4-byte address (`mbAddr`) depending on the command. Command-specific
exceptions are documented in `PROTOCOL.md`.

Example — battery read (`RaceCmdGetBattery`, ID `3074 = 0x0C02`):

```
TX : 05 5A 02 00 02 0C
RX : 05 5B <len> <len> 02 0C <status> <batteries...>
```

Response mapped to `com.airoha.sdk.api.message.AirohaBatteryInfo`:
`masterLevel`, `masterCharging`, `masterVoltage`, `slaveLevel`,
`slaveCharging`, `slaveVoltage`, `boxLevel`, `boxCharging`.

## 6. Notable commands

Full list: `libbase/RaceCommand/constant/RaceId.java` (142 entries, table in the
annex).

| Domain | Examples | ID |
|---|---|---|
| Battery | `RACE_GET_BATTERY` | 3074 (0x0C02) |
| TWS / case battery | `RACE_BLUETOOTH_TWS_GET_BATTERY` / `..._BOX_BATTERY` | 3286 (0x0CD6) / 11 (0x000B) |
| ANC (legacy) | `RACE_ANC_ON` / `OFF` / `GET_STATUS` / `SET_GAIN` | 4608–4613 |
| Real-time DSP ANC | `DSP_REALTIME_ANC_ON`, `..._GAIN_CONTROL`, `..._ADAPTIVE_RESULT` | 3590–3622 |
| EQ / PEQ | `DSP_REALTIME_PEQ`, `RACE_HOSTAUDIO_PEQ_SAVE_STATUS` | 3587 / 2557 |
| Find me | `RACE_FIND_ME` / `_QUERY_STATE` | 11265 / 11264 |
| Key action | `RACE_MMI_KEY_COMMAND`, `RACE_READ_KEY_ACTION_NVKEY_FROM_RAM` | 4353 / 2571 |
| NVKEY | `RACE_NVKEY_READFULLKEY`, `WRITEFULLKEY`, `RECLAIM`, `RELOAD_NVKEY_TO_RAM` | 2560–2573 |
| FOTA | `RACE_FOTA_COMMIT`, `PARTITION_INFO_QUERY`, `INTEGRITY_CHECK`, `PING` | 7168–7195 |
| Flash | `RACE_FLASH_SECTOR_ERASE`, `PAGE_PROGRAM`, `SET_PROTECT_BIT` | 1794–1807 |
| Dump | `RACE_ANCDUMP_ONOFF`, `RACE_AIRDUMP_ONOFF` | 3594 / 3595 |
| TWS relay | `RACE_*_DUAL_DEVICES_*`, `RaceCmdRelayPass` | 7184–7189 |

The app also ships an **engineer mode** with `CustomCmdFragment`, which can send
an arbitrary hexadecimal frame (`host.send(bytes)`) or use
`sendCustomCommand`, plus manual configuration of the connection UUIDs.

## 7. Firmware and backend chain

Two independent update paths exist.

**App (APK) update — `com.airoha.yfy.AutoUpdater`**

- Version feed over cleartext HTTP:
  `http://apkdown.198509.xyz/apk_update/output.json`.
- The JSON is parsed with regexes for `outputFile` and `versionName`, then the
  dotted versions are compared by string segment (`compareAppVersion`).
- If the remote version is newer, a dialog offers "download now" / "later"; the
  download is refused outside Wi-Fi unless the user overrides it.
- The APK is streamed to
  `getExternalFilesDir(DOWNLOADS)/Flycc.apk` and installed with a
  `FileProvider` `ACTION_VIEW` intent (`REQUEST_INSTALL_PACKAGES`).
- No checksum, signature or TLS validation is performed. "Download later" kills
  the process.
- The check runs from the "Mine" settings page (and after its permission
  result), not on every launch. `getAutoUpdateFlag()` always returns `1`, so
  the stored toggle is ignored.

**Earbud firmware (FOTA)**

- Update discovery over cleartext HTTP:
  - `http://twsfota.198509.xyz/tws_fota_bin/AB157x/` (AB157x/AB1565/AB1568)
  - `http://twsfota.198509.xyz/tws_fota_bin/` (AB1562)
  - `http://twsfota.198509.xyz/headset_fota_bin/` (single)
  - `http://msg.198509.xyz/` and `http://user.198509.xyz/add` (user/device info)
- Discovery uses two Java-Properties XML files, `fota` and `update_info`
  (`_eng` outside CN), under `<Company>/<Module>/`; the package path and file
  naming are documented in [`PROTOCOL.md`](PROTOCOL.md) §5.6.
- Firmware is checked with CRC8/CRC16 on the app side (`libutils/CRC8`,
  `CRC16`); the flash stage writes a per-page CRC8. There is no application-level
  signature on the app side.
- The transfer sequence, command IDs and payloads (`0x1C00`–`0x1C1C`, `0x0402`,
  `0x0404`, `0x0431`, `0x0433`) are documented in
  [`PROTOCOL.md`](PROTOCOL.md) §5.6.
- Telemetry: `YfySendUserInfo`, `MineFragment`; device registration through the
  RACE frame `MESSAGE_DEVICE = {05 5A 09 00 00 21 09 44 09 45 56 49 43 ...}`.
  The original report labels this ID `0x0921`; the bytes decode to **`0x2100`**
  (`00 21` little-endian).

## 8. Security findings

- No encryption or MAC on RACE frames: the GATT/SPP link is enough.
- No application authentication or pairing constraint in the SDK.
- Dangerous capabilities exposed: flash and RAM read/write, NVKEY read, DSP
  dump.
- Cleartext HTTP backend and OTA, APK auto-update enabled.
- The Baidu packer protects the code, not the protocol: a memory dump is enough.

## 9. Artifacts and reproduction

| Artifact | Path |
|---|---|
| Dumped dex (39) | `/tmp/opencode/flycc/dexdump/` |
| SDK dex | `classes05.dex` (and partial duplicate `classes06.dex`) |
| JADX sources | `/tmp/opencode/flycc/jadx-sdk/sources` |
| apktool output | `/tmp/opencode/flycc/apktool-out` |
| Baidu map neutralization script | `/tmp/opencode/flycc/noop_baidu_map.js` |
| Load-time dump script | `/tmp/opencode/flycc/dump_dex.js` |

Reproduction:

```bash
export PATH=/opt/android-sdk/emulator:/opt/android-sdk/platform-tools:$PATH
emulator -avd flycc -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -memory 2048 &
adb wait-for-device && adb root
adb install -r rea/Flycc.ver.2.0.35.build.2023052214.apk
adb push /opt/frida/frida-server-17.26.2-x86_64 /data/local/tmp/frida-server
adb shell "chmod 755 /data/local/tmp/frida-server; nohup /data/local/tmp/frida-server >/dev/null 2>&1 &"
/opt/frida-venv/bin/frida -U -f com.airoha.twshelper -l noop_baidu_map.js &
/opt/frida-venv/bin/frida-dexdump -U -n com.airoha.twshelper -d -o dexdump
jadx -d jadx-sdk --no-res dexdump/classes05.dex dexdump/classes06.dex
```

## Annex — RaceId (name, decimal, hex)

| Name | Dec | Hex |
|---|---:|---|
| `DSP_ANC_USER_TRIGGER_RECORD_RESQUEST_QUERY` | 3606 | 0x0E16 |
| `DSP_AUDIO_FEATURE_CAPABILITY` | 3632 | 0x0E30 |
| `DSP_REALTIME_ANC_ADAPTIVE_CHECK` | 3603 | 0x0E13 |
| `DSP_REALTIME_ANC_ADAPTIVE_RESULT` | 3604 | 0x0E14 |
| `DSP_REALTIME_ANC_GAIN_CONTROL` | 3607 | 0x0E17 |
| `DSP_REALTIME_ANC_NOTIFY_GAIN` | 3605 | 0x0E15 |
| `DSP_REALTIME_ANC_NOTIFY_GAIN_1562` | 3608 | 0x0E18 |
| `DSP_REALTIME_ANC_ON` | 3590 | 0x0E06 |
| `DSP_REALTIME_ANC_SET_RUNTIME_VOL` | 3590 | 0x0E06 |
| `DSP_REALTIME_FULL_ADAPTIVE_ANC_MODE` | 3622 | 0x0E26 |
| `DSP_REALTIME_LEAKAGE_DETECTION` | 3603 | 0x0E13 |
| `DSP_REALTIME_PEQ` | 3587 | 0x0E03 |
| `RACE_AIRDUMP_ONOFF` | 3595 | 0x0E0B |
| `RACE_ANCDUMP_ONOFF` | 3594 | 0x0E0A |
| `RACE_ANC_GET_STATUS` | 4610 | 0x1202 |
| `RACE_ANC_OFF` | 4609 | 0x1201 |
| `RACE_ANC_ON` | 4608 | 0x1200 |
| `RACE_ANC_READ_PARAM_FROM_NVKEY` | 4612 | 0x1204 |
| `RACE_ANC_SET_GAIN` | 4611 | 0x1203 |
| `RACE_ANC_WRITE_GAIN_TO_NVKEY` | 4613 | 0x1205 |
| `RACE_ANTENNAUT_REPORT_ENABLE` | 5888 | 0x1700 |
| `RACE_BLUETOOTH_GET_BOX_BATTERY` | 11 | 0x000B |
| `RACE_BLUETOOTH_GET_CHARGING_INFO` | 9 | 0x0009 |
| `RACE_BLUETOOTH_GET_CLIENT_EXISTENCE` | 3283 | 0x0CD3 |
| `RACE_BLUETOOTH_GET_IR_STATUS` | 12 | 0x000C |
| `RACE_BLUETOOTH_IS_AGENT_RIGHT_DEVICE` | 3284 | 0x0CD4 |
| `RACE_BLUETOOTH_ROLE_SWITCH` | 3287 | 0x0CD7 |
| `RACE_BLUETOOTH_TWS_GET_BATTERY` | 3286 | 0x0CD6 |
| `RACE_CODEC_A2DP_COEDC_INFO` | 3269 | 0x0CC5 |
| `RACE_DEVICE_OPERATION` | 11398 | 0x2C86 |
| `RACE_FIND_ME` | 11265 | 0x2C01 |
| `RACE_FIND_ME_QUERY_STATE` | 11264 | 0x2C00 |
| `RACE_FLASH_DUAL_DEVICES_PARTITION_ERASE` | 1807 | 0x070F |
| `RACE_FLASH_PAGE_PROGRAM` | 1799 | 0x0707 |
| `RACE_FLASH_SECTOR_ERASE` | 1796 | 0x0704 |
| `RACE_FLASH_SET_PROTECT_BIT` | 1794 | 0x0702 |
| `RACE_FOTA_ACTIVE_FOTA_PREPARATION` | 7193 | 0x1C19 |
| `RACE_FOTA_COMMIT` | 7170 | 0x1C02 |
| `RACE_FOTA_DUAL_DEVICES_CANCEL` | 7189 | 0x1C15 |
| `RACE_FOTA_DUAL_DEVICES_COMMIT` | 7185 | 0x1C11 |
| `RACE_FOTA_DUAL_DEVICES_QUERY_PARTITION_INFO` | 7188 | 0x1C14 |
| `RACE_FOTA_DUAL_DEVICES_QUERY_STATE` | 7186 | 0x1C12 |
| `RACE_FOTA_DUAL_DEVICES_START_TRANSACTION` | 7184 | 0x1C10 |
| `RACE_FOTA_DUAL_DEVICES_WRITE_STATE` | 7187 | 0x1C13 |
| `RACE_FOTA_GET_A2DP_CONNECTION_INFO` | 8 | 0x0008 |
| `RACE_FOTA_GET_AE_INFO` | 7177 | 0x1C09 |
| `RACE_FOTA_GET_INTERNAL_FLASH_PARTITION_SHA256` | 7183 | 0x1C0F |
| `RACE_FOTA_GET_PARTITION_ERASE_STATUS` | 7190 | 0x1C16 |
| `RACE_FOTA_GET_ROFS_VERSION` | 7426 | 0x1D02 |
| `RACE_FOTA_GET_VERSION` | 7175 | 0x1C07 |
| `RACE_FOTA_INTEGRITY_CHECK` | 7169 | 0x1C01 |
| `RACE_FOTA_PARTITION_INFO_QUERY` | 7168 | 0x1C00 |
| `RACE_FOTA_PING` | 7195 | 0x1C1B |
| `RACE_FOTA_QUERY_STATE` | 7172 | 0x1C04 |
| `RACE_FOTA_SET_IR_ONOFF` | 10 | 0x000A |
| `RACE_FOTA_SET_POWER_CONTROL_INFO` | 7 | 0x0007 |
| `RACE_FOTA_START` | 7176 | 0x1C08 |
| `RACE_FOTA_START_TRANSCATION` | 7178 | 0x1C0A |
| `RACE_FOTA_STOP` | 7171 | 0x1C03 |
| `RACE_FOTA_WRITE_STATE` | 7174 | 0x1C06 |
| `RACE_GET_A2DP_PARAMETER` | 3279 | 0x0CCF |
| `RACE_GET_ANC_SPORTS_MODE_STATUS` | 13 | 0x000D |
| `RACE_GET_BATTERY` | 3074 | 0x0C02 |
| `RACE_GET_BOOT_REASON` | 7680 | 0x1E00 |
| `RACE_GET_BUILD_VERSION_INFO` | 7688 | 0x1E08 |
| `RACE_GET_DEVICE_COLOR_INDEX` | 14 | 0x000E |
| `RACE_GET_DUMP_ADDR` | 7682 | 0x1E02 |
| `RACE_GET_DUMP_REGION_INFO` | 7684 | 0x1E04 |
| `RACE_GET_EXCEPTION_LOG_ADDR` | 7686 | 0x1E06 |
| `RACE_GET_IN_EAR_ON_OFF` | 11281 | 0x2C11 |
| `RACE_GET_LE_LINK_STATUS` | 3282 | 0x0CD2 |
| `RACE_GET_MMI_COMMON_CONFIG` | 11395 | 0x2C83 |
| `RACE_GET_OFFLINE_LOG_INFO` | 7685 | 0x1E05 |
| `RACE_GET_POWER_MODE` | 524 | 0x020C |
| `RACE_GET_ROLE` | 3268 | 0x0CC4 |
| `RACE_GET_SDK_VERSION` | 769 | 0x0301 |
| `RACE_GET_SHARE_MODE` | 5121 | 0x1401 |
| `RACE_HA_CONFIG` | 11399 | 0x2C87 |
| `RACE_HOSTAUDIO_MMI_GET_ENUM` | 2305 | 0x0901 |
| `RACE_HOSTAUDIO_MMI_SET_ENUM` | 2304 | 0x0900 |
| `RACE_HOSTAUDIO_PEQ_SAVE_STATUS` | 2557 | 0x09FD |
| `RACE_JLAB_TOUCH_ACTIVATE` | 14 | 0x000E |
| `RACE_KEYCODE_REBOOT` | 4353 | 0x1101 |
| `RACE_LEAUDIO_BIG_INFO_IND` | 8713 | 0x2209 |
| `RACE_LEAUDIO_BIS_RESET` | 8708 | 0x2204 |
| `RACE_LEAUDIO_BIS_SYNC_IND` | 8706 | 0x2202 |
| `RACE_LEAUDIO_BIS_TERMINATED_IND` | 8707 | 0x2203 |
| `RACE_LEAUDIO_EA_REPORT_IND` | 8704 | 0x2200 |
| `RACE_LEAUDIO_GET_BIS_STATE` | 8712 | 0x2208 |
| `RACE_LEAUDIO_PA_REPORT_IND` | 8705 | 0x2201 |
| `RACE_LEAUDIO_PLAY_BIS` | 8706 | 0x2202 |
| `RACE_LEAUDIO_SCAN_BROADCAST_SOURCE` | 8704 | 0x2200 |
| `RACE_LEAUDIO_SELECT_BROADCAST_SOURCE` | 8705 | 0x2201 |
| `RACE_LEAUDIO_SET_BROADCAST_CODE` | 8713 | 0x2209 |
| `RACE_LEAUDIO_STOP_BIS` | 8707 | 0x2203 |
| `RACE_MMI_KEY_COMMAND` | 4353 | 0x1101 |
| `RACE_NVKEY_READFULLKEY` | 2560 | 0x0A00 |
| `RACE_NVKEY_READFULLKEY_RETURN_NVID` | 2572 | 0x0A0C |
| `RACE_NVKEY_RECLAIM` | 2563 | 0x0A03 |
| `RACE_NVKEY_WRITEFULLKEY` | 2561 | 0x0A01 |
| `RACE_NVKEY_WRITEFULLKEY_RETURN_NVID` | 2573 | 0x0A0D |
| `RACE_OFFLINE_GET_LOG_TO_FLASH_SWITCH` | 7700 | 0x1E14 |
| `RACE_OFFLINE_SET_LOG_TO_FLASH_SWITCH` | 7701 | 0x1E15 |
| `RACE_ONLINELOG_RX_PACKAGE` | 65535 | 0xFFFF |
| `RACE_ONLINE_LOG_OVER_BT_START` | 7689 | 0x1E09 |
| `RACE_ONLINE_LOG_QUERY_CPU_FILTER_INFO` | 7692 | 0x1E0C |
| `RACE_ONLINE_LOG_QUERY_MODULE_INFO` | 7690 | 0x1E0A |
| `RACE_ONLINE_LOG_SAVE_CPU_FILTER` | 7694 | 0x1E0E |
| `RACE_ONLINE_LOG_SET_CPU_FILTER_INFO` | 7693 | 0x1E0D |
| `RACE_ONLINE_LOG_SET_MODULE_INFO` | 7691 | 0x1E0B |
| `RACE_ONLINE_LOG_TYPE_MSG_ID_LOG` | 3857 | 0x0F11 |
| `RACE_ONLINE_LOG_TYPE_TEXTURE_LOG` | 3856 | 0x0F10 |
| `RACE_READ_KEY_ACTION_NVKEY_FROM_RAM` | 2571 | 0x0A0B |
| `RACE_READ_SFR` | 513 | 0x0201 |
| `RACE_RELAY_GET_AVA_DST` | 3328 | 0x0D00 |
| `RACE_RELAY_PASS_TO_DST` | 3329 | 0x0D01 |
| `RACE_RELOAD_NVKEY_TO_RAM` | 2569 | 0x0A09 |
| `RACE_RESUME_DSP` | 3586 | 0x0E02 |
| `RACE_SET_DEVICE_PUSH_STATUS_ONOFF` | 6 | 0x0006 |
| `RACE_SET_IN_EAR_ON_OFF` | 11280 | 0x2C10 |
| `RACE_SET_KEY_ACTION_NVKEY_TO_RAM` | 2570 | 0x0A0A |
| `RACE_SET_LE_CONNECTION_PARAM` | 3281 | 0x0CD1 |
| `RACE_SET_MMI_COMMON_CONFIG` | 11394 | 0x2C82 |
| `RACE_SOFTWARE_RESET` | 7173 | 0x1C05 |
| `RACE_SPIFLASH_DUAL_DEVICES_PARTITION_ERASE` | 1043 | 0x0413 |
| `RACE_STORAGE_BYTE_PROGRAM` | 1024 | 0x0400 |
| `RACE_STORAGE_DUAL_DEVICES_PARTITION_ERASE` | 1074 | 0x0432 |
| `RACE_STORAGE_EMPTY_KE` | 1264 | 0x04F0 |
| `RACE_STORAGE_GET_4K_ERASED_STATUS` | 1075 | 0x0433 |
| `RACE_STORAGE_GET_PARTITION_SHA256` | 1073 | 0x0431 |
| `RACE_STORAGE_LOCK_UNLOCK` | 1072 | 0x0430 |
| `RACE_STORAGE_PAGE_PROGRAM` | 1026 | 0x0402 |
| `RACE_STORAGE_PAGE_READ` | 1027 | 0x0403 |
| `RACE_STORAGE_PARTITION_ERASE` | 1028 | 0x0404 |
| `RACE_STORAGE_READ_MANUFACTURER_AND_MEMORYTYPE` | 1184 | 0x04A0 |
| `RACE_STORAGE_READ_OTP` | 1186 | 0x04A2 |
| `RACE_STORAGE_READ_PAGE` | 1027 | 0x0403 |
| `RACE_STORAGE_SET_CONFIGURATION_REGISTER` | 1185 | 0x04A1 |
| `RACE_SUSPEND_DSP` | 3585 | 0x0E01 |
| `RACE_SWITCH_POWER_MODE` | 525 | 0x020D |
| `RACE_TOOL_ASSERT` | 7687 | 0x1E07 |
| `RACE_TRIGGER_LOG_DUMP` | 7683 | 0x1E03 |
