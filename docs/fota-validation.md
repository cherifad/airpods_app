# FOTA validation capture

Goal: turn the static FOTA documentation (`PROTOCOL.md` §5.6) into validated
observations, without risking an unbootable pair.

An earbud that reports **"already latest version"** is still useful: the app has
already performed the DFU queries and the HTTP update lookup, which is where
most of the remaining unknowns are.

## 1. Bluetooth capture (device ↔ app)

This gives the real `0x1C00` / `0x1C14` / `0x1C07` / `0x1C04` responses and
confirms every field offset.

1. Enable **Developer options → Enable Bluetooth HCI snoop log**.
2. Reboot or toggle Bluetooth so logging starts.
3. Open FlyCC → FOTA page → **query update**; wait for "already latest".
4. Leave the page, then pull the log:

```bash
# Rooted phone:
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log

# No root: the bug report contains the same file.
adb bugreport bugreport.zip
unzip -p bugreport.zip 'FS/data/misc/bluetooth/logs/btsnoop_hci.log' > btsnoop_hci.log
```

5. Decode it:

```bash
python3 tools/race_btsnoop.py btsnoop_hci.log --summary
python3 tools/race_btsnoop.py btsnoop_hci.log --handle 0x0011
```

The script prints `cmd=0x1C00`, `0x1C14`, `0x1C07`, `0x0402`, … with the exact
bytes. Send the log or the decoded output.

## 2. HTTP capture (app ↔ vendor server)

The update discovery is cleartext HTTP, so no certificate work is needed.

- Easiest, no root: **PCAPdroid** (VPN-based), filter `port 80` and host
  `198509.xyz`.
- Or share the phone's Wi-Fi through a laptop hotspot and run
  `tcpdump -i any -w fota.pcap 'tcp port 80 and host 198509.xyz'`.

Capture while doing the same "query update" action. The interesting requests
are:

```
GET /tws_fota_bin/AB157x/<Company>/<Module>/fota
GET /tws_fota_bin/AB157x/<Company>/<Module>/update_info_eng
```

Keep the response bodies. They are Java `Properties` XML files. The keys are
firmware version strings and the values name the package, so the file may still
contain the latest package path under older-version keys even when your unit is
up to date.

## 3. Optional: download-only package capture

To obtain a firmware package and exercise the download path without writing
flash:

1. Read the `fota` file from step 2 and pick a key (an older version string).
2. Start FlyCC with the probe:

```bash
# Rooted phone, Frida 16.x on the host
frida -U -f com.airoha.twshelper -l tools/flycc_fota_probe.js
```

3. Set `FORCE_VERSION` in the script to that key. The app now finds an update
   and starts the download; `BLOCK_TRANSFER = true` stops it before
   `startDataTransfer`, so flash is never touched.

Frida setup on a rooted phone (host commands):

```bash
python3 -m venv /tmp/frida-venv
/tmp/frida-venv/bin/pip install frida==16.7.19 frida-tools

# arm64-v8a; use android-x86_64 for an emulator
adb shell getprop ro.product.cpu.abi
curl -L -o /tmp/frida-server.xz \
  https://github.com/frida/frida/releases/download/16.7.19/frida-server-16.7.19-android-arm64.xz
unxz -f /tmp/frida-server.xz
adb push /tmp/frida-server /data/local/tmp/frida-server
adb shell su -c 'chmod 755 /data/local/tmp/frida-server'
adb shell su -c '/data/local/tmp/frida-server &'

/tmp/frida-venv/bin/frida -U -f com.airoha.twshelper -l tools/flycc_fota_probe.js
```

Skip this step if the phone is not rooted: the DFU and HTTP captures from
sections 1 and 2 are enough for the first validation pass.

4. Pull the downloaded file (the app stores it under its external files dir):

```bash
adb shell ls -R /sdcard/Android/data/com.airoha.twshelper/files/
adb pull /sdcard/Android/data/com.airoha.twshelper/files/AB157xFota/...
```

The file is enough to validate the container, the naming and the CRC/page
layout offline. Do **not** let the transfer run until the package is validated.

## 4. What each capture closes

| Unknown | Closed by |
|---|---|
| Company/Module and package names | HTTP `fota` / `update_info` bodies |
| `0x1C00`/`0x1C14` field offsets and role bytes | BLE capture of the DFU query |
| `0x1C07` version string format | BLE capture |
| `0x1C04` state semantics | BLE capture |
| Package container/compression/signature | downloaded package + `binwalk`/hexdump |
| Erase/program payloads (`0x0404`, `0x0402`, CRC8) | full transfer capture on a bench pair |
| Mode differences (background/active/adaptive) | repeat the capture per mode |
| Device acceptance rules | only a real update run, on a recoverable pair |

## 5. Safety

- Battery above 50 % (FlyCC uses 50–70 % thresholds) and a stable link.
- Validate the package naming and size before any write.
- Use a replaceable/bench pair for the first full run.
- Keep the previous firmware package and a recovery path.
