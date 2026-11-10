# RACE Protocol — Sonabud

> Reference document for the BLE/RACE layer of **Sonabud**.
> It covers the transport, frame format, initialization sequence and the payloads
> needed to control Airoha AB156x/AB158x earbuds.
>
> **Primary source**: static analysis of the Flycc APK
> `com.airoha.twshelper` (version 2.0.35, build 2023052214, SHA-256
> `2f00a9db722da4fa787be12b63a91c30f7d067f76f95664d3787ce6228323ef1`) and of the
> decompiled Airoha SDK in `/tmp/opencode/flycc/jadx-sdk/sources`.
>
> **Status**: working document produced by reverse engineering. Formats marked
> “inferred” or “not observed” must be confirmed on a real device before being
> considered stable.

---

## 1. Overview

RACE is Airoha's proprietary binary protocol. There is no JSON, no
cryptographic handshake and no application-level authentication: commands are
sent in clear over the Bluetooth link.

```
Android app
        │
        ├── BLE GATT (primary) ── Airoha proprietary characteristics
        └── RFCOMM/SPP (variant) ── Bluetooth Classic serial socket
                    │
                    ▼
               RACE frame
                    │
                    ▼
             Airoha firmware
```

Key properties:

- all multi-byte fields are **little-endian**;
- a command is identified by a 16-bit **RACE ID**;
- the firmware replies with the same ID and a status byte (with exceptions);
- the protocol exposes destructive operations (flash, RAM, NVKEY, FOTA).
  **Sonabud must only expose the read commands and ANC settings it
  needs.** Do not add an “arbitrary hex command” screen to the consumer app.

---

## 2. Transport

### 2.1 BLE GATT (recommended)

| Purpose | UUID |
|---|---|
| Airoha service | `5052494D-2DAB-0341-6972-6F6861424C45` |
| TX — host write → earbuds | `43484152-2DAB-3241-6972-6F6861424C45` |
| RX — earbuds notification → host | `43484152-2DAB-3141-6972-6F6861424C45` |
| Standard CCCD | `00002902-0000-1000-8000-00805F9B34FB` |

Connection procedure:

1. Scan with a filter on the `5052494D-…` service (or connect to an
   already-bonded device).
2. `connectGatt(context, false, callback, TRANSPORT_LE)`.
3. Discover services, then get the Airoha service and the TX/RX
   characteristics.
4. Enable notifications on **RX** by writing `01 00` to its CCCD
   (`BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE`).
5. Request an MTU of **240**. The Airoha SDK then uses a payload size of
   `MTU - 3` (237 bytes with MTU 240).
6. Send raw RACE bytes on **TX**, using write-with-response
   (`WRITE_TYPE_DEFAULT`).

A RACE frame may be split across several GATT writes when it exceeds the
usable payload size. On reception, notifications may contain:

- one complete frame;
- one frame fragment;
- several concatenated frames.

You must therefore **buffer** incoming bytes and split on the `length` field
(see §3); never assume that one notification equals one frame.

### 2.2 SPP / RFCOMM (variant)

The SDK also supports Bluetooth Classic:

| Purpose | UUID |
|---|---|
| Airoha SPP | `00000000-0000-0000-0099-AABBCCDDEEFF` |
| Standard SPP | `00001101-0000-1000-8000-00805F9B34FB` |
| Alternative TRSPX service | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` |
| TRSPX TX / RX | `49535343-8841-43F4-A8D4-ECBE34729BB3` / `49535343-1E4D-4BD9-BA61-23C647249616` |

The RFCOMM stream carries the same RACE frames with no extra header.
Fragmentation and reassembly work the same way as over GATT.

### 2.3 Encapsulation

- The Airoha SDK uses an internal transport called `H4Transport`. Despite its
  name, **it does not prefix RACE frames**: it distinguishes HCI events
  (`0x04`), ACL packets (`0x02`) and RACE frames (`0x05`/`0x15`), then chunks
  outgoing writes. RACE frames keep their `0x05` / `0x15` header.
- `BypassTransport` also sends raw RACE bytes.
- `NmeaTransport` is an ASCII variant used by some tools, not by Flycc in
  normal mode.

---

## 3. RACE frame

### 3.1 Format

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 1 | Head | `0x05` MMI command, `0x15` FOTA |
| 1 | 1 | Type | `0x5A` request, `0x5B` response, `0x5C` no-response, `0x5D` indication |
| 2 | 2 | Length (LE) | `2 + payload size` (the ID is included) |
| 4 | 2 | Cmd ID (LE) | RACE identifier |
| 6 | n | Payload | command-specific |

The total frame size is therefore:

```
total = length + 4
```

with `length` read at offsets 2 and 3. The SDK parser rejects frames whose
`length` is 0, whose total size is below 6 or above 2000 bytes. The `0x07`
seen in the H4 transport is an HCI event type, not a RACE header: do not accept
it as an application frame.

### 3.2 Types

| Constant | Value | Meaning |
|---|---|---|
| `CMD_NEED_RESP` | `0x5A` | request with expected response |
| `RESPONSE` | `0x5B` | response |
| `CMD_NO_RESP` | `0x5C` | command without response |
| `INDICATION` | `0x5D` | unsolicited notification |

### 3.3 Minimal example

Read the NVDM version (NVKEY 4097 = `0x1001`):

```
TX : 05 5A 06 00 00 0A 01 10 E8 03
     │  │  │     │     └─ requested length: 1000 bytes (0x03E8)
     │  │  │     └─ NVKEY ID 0x1001 in little-endian
     │  │  └─ length = 6 = ID (2) + payload (4)
     │  └─ type = request
     └─ MMI head
```

Possible response:

```
RX : 05 5B 05 00 00 0A 01 00 0C
     │  │  │     │     │  └─ NVKEY data (version 0x0C here)
     │  │  │     │     └─ data length = 1
     │  │  │     └─ ID 0x0A00
     │  │  └─ length = 5 = ID (2) + payload (3)
     │  └─ type = response
     └─ MMI head
```

### 3.4 Response status: general rule and exceptions

The SDK's general rule is:

```
status = frame[6]   // first payload byte
```

`0` means success; any other value is an error (the detailed semantics are not
documented). There are several important exceptions:

| Command | Special case |
|---|---|
| `0x0A00` NVKEY read | no status: `[length LE u16][data]` starting at `frame[6]` |
| `0x0900` / `0x0901` MMI enum | status at `frame[8]`, after the 16-bit module ID |
| `0x0D00` available destinations | no status: list of `(type, id)` pairs starting at `frame[6]` |
| `0x0D01` relay | the status is the inner frame's status, extracted from `frame[8]` |
| `0x0301` SDK version | special format, not verified here and not used by Sonabud |

For the usual commands (`0x0CD6`, `0x0E06`, `0x1200`, `0x1201`), the status is
indeed `frame[6]`.

### 3.5 Matching rules

- A response must have the **same ID** as the request and type `0x5B`.
- An indication may arrive without a request (type `0x5D`); it must not be
  consumed by the response queue.
- The SDK waits for **one response at a time**: it sends a command, waits for
  the response or a timeout (1000 ms by default), then moves on.
- On failure, the SDK retries up to 3 times.

## 4. Initialization sequence

The sequence below is the one used by the `AirohaCommonControl` layer after
GATT discovery. It identifies the chip, device type, NVDM version, TWS role and
the peer ID when present.

| # | Command | Request (hex) | Expected response | Interpretation |
|---|---|---|---|---|
| 1 | Read chip name — NVKEY 4098 (`0x1002`) | `05 5A 06 00 00 0A 02 10 E8 03` | `05 5B .. .. 00 0A <len LE> <ASCII>` | `len` = `frame[6..7]`, ASCII data starts at `frame[8]` |
| 2 | Read device type — NVKEY `0x183D` or `0xF2B0` | `05 5A 06 00 00 0A 3D 18 E8 03` or `05 5A 06 00 00 0A B0 F2 E8 03` | `05 5B .. .. 00 0A <len LE> <data>` | type at `frame[8]`, AWS mode at `frame[47]` when the frame is at least 48 bytes |
| 3 | Read NVDM version — NVKEY 4097 (`0x1001`) | `05 5A 06 00 00 0A 01 10 E8 03` | `05 5B .. .. 00 0A <len LE> <data>` | version at `frame[8]` |
| 4 | Read role — `0x0CC4` | `05 5A 02 00 C4 0C` | `05 5B 04 00 C4 0C <status> <role>` | `status` at `frame[6]`, `role` at `frame[7]` |
| 5 | List destinations — `0x0D00` | `05 5A 02 00 00 0D` | `05 5B .. .. 00 0D <type> <id> ...` | `(type, id)` pairs starting at `frame[6]`; look for `type = 5` (AWSPEER) |

### 4.1 Device type ID selection

The SDK first reads the chip name (step 1), then chooses the NVKEY ID:

- if the chip is `AB158x`, `AB1568_V3`, `AB1565_DUAL_V3`, `AB1568_DUAL_V3`,
  `AB158x_DUAL` or `AB157x` → NVKEY `0x183D` (6205);
- otherwise → NVKEY `0xF2B0` (-3408), which includes a plain AB1565.

The 1562 MMI layer reads `0xF2B0` directly to load its configuration. Both
values are legitimate; do not treat them as aliases.

### 4.2 Field interpretation

**Chip name (`0x1002`)**

- `frame[6..7]`: data length `L` (little-endian).
- `frame[8..8+L-1]`: ASCII string, for example `AB1565`.
- If `L == 0`, the SDK considers the response invalid and retries (3 times).

**Device type (`0x183D` / `0xF2B0`)**

- `frame[8]`: type. The common parser interprets `0` as a headset. For a
  non-zero type, it checks the AWS mode:
  - `type == 0` → headset;
  - otherwise, `AWS mode` (`frame[47]` when present) equal to `0` or `2` → earbuds;
  - otherwise → headset.
- `frame[47]`: AWS mode, only read when the frame is at least 48 bytes.
- The `DeviceType` enum exposes `0` = `HEADSET` and `1` = `EARBUDS`, but the
  common parser does not limit itself to `1`: it combines the type and AWS mode.

**NVDM version (`0x1001`)**

- `frame[8]`: version byte.

**Role (`0x0CC4`)**

- `frame[6]`: status.
- `frame[7]`: role, using the SDK mapping:
  - `0` = normal;
  - `1` = master;
  - `2` = slave.
- The SDK also exposes `101`/`102` (`TWS_MASTER`/`TWS_SLAVE`) in its enum; these
  are API values and are not necessarily present on the wire. The `0x0CC4`
  parser reads one byte and expects the low values `0/1/2`.

**Available destinations (`0x0D00`)**

- The payload is a list of `(type, id)` pairs **without a status byte**,
  starting at `frame[6]`.
- Known types: `0` UART, `1` USB, `2` AirApp, `3` software, `4` AirUpdate,
  `5` AWSPEER.
- `CommonStageGetAvaDst` forces the status to `0` after parsing. The MMI layer,
  however, uses `frame[6]` as the generic status: if the first pair is not
  `(0, 0)`, the command may be treated as a failure. Use a dedicated `0x0D00`
  parser that ignores this status byte.
- If a `type = 5` pair exists, its `id` is the TWS peer ID. Otherwise there is
  no peer: partner commands must be disabled.

### 4.3 Practical flow

1. Wait for `onMtuChanged` (or a fallback delay) after requesting the MTU.
2. Send step 1 and wait for the complete response.
3. Run steps 2 to 5 in order.
4. Do not send the next step until the previous one has responded or timed out.
5. If step 5 fails, treat the device as “solo” and hide the partner data.

---

## 5. Useful commands

### 5.1 TWS battery — `0x0CD6`

This is the command used by the 1562 layer to read one earbud's level.

**Local request (agent)**

```
05 5A 03 00 D6 0C 00
```

- payload: `00` (1 byte);
- the 1562 layer sends `0`; the older `libmmi` layer sent `0` for the agent and
  `1` for the partner.

**Response**

```
05 5B 05 00 D6 0C <status> <agentOrClient> <level>
```

| Offset | Field | Meaning |
|---|---|---|
| 6 | `status` | `0` = success |
| 7 | `agentOrClient` | `0` = agent/local, `1` = partner depending on the variant |
| 8 | `level` | raw level; the SDK stores it in `masterLevel`/`slaveLevel` |

The 1562 SDK ignores this byte and relies on the request context (local or
relayed). The level is one byte, stored directly in
`AirohaDevice.batteryInfo` and copied to `AirohaBatteryInfo.masterLevel` /
`slaveLevel` without conversion; the UI displays it as a percentage, so
`0–100` is expected. The exact semantics outside this range are not documented.

**Reading the partner (relay `0x0D01`)**

Wrap the inner frame in a relay to the AWSPEER destination
(`type = 5`, `id = peerId`):

```
05 5A 0B 00 01 0D 05 <peerId> 05 5A 03 00 D6 0C 00
│  │  │     │     │  │        └─ inner 0x0CD6 frame
│  │  │     │     │  └─ peer id (e.g. 01)
│  │  │     │     └─ AWSPEER type = 5
│  │  │     └─ relay ID 0x0D01
│  │  └─ length = 11 = 2 (ID) + 2 (dst) + 7 (inner frame)
│  └─ type = request
└─ head
```

> Length note: the inner frame is 7 bytes (`05 5A 03 00 D6 0C 00`). The relay
> payload is therefore `2 + 7 = 9`, and the `length` field is `2 + 9 = 11`
> (`0x0B`). That is the `0B 00` in the example.

**Relay response**

```
05 5B 0D 00 01 0D <addrType> <addrId> 05 5B 05 00 D6 0C <status> <agentOrClient> <level>
```

The inner frame starts at offset **8** (after the 6-byte outer header and the
2 address bytes). The SDK extracts it with
`Arrays.copyOfRange(frame, 8, frame.length)`.

**Recommendation**: query the agent, then the partner (if present), and repeat
about every 30 s and after every reconnection.

**Legacy command `0x0C02`**

`RACE_GET_BATTERY` (3074 = `0x0C02`, no payload) exists in the table, and the
report associates it with `AirohaBatteryInfo` (master/slave/case, charging,
voltage). The exact parser was not found in the 1562 SDK; the command is not
used by the AB1562 path. Do not use it as the primary source.

### 5.2 ANC

The 1562 SDK does not use `0x1200`/`0x1201` for ANC despite their presence in
`RaceId`. The path used by the 1562 SDK is `0x0E06` with subcommands, and state
reading goes through `0x0901`.

#### 5.2.1 Enable / disable — `0x0E06`

| Action | Payload | Full frame |
|---|---|---|
| Enable with filter | `00 0A <filter> [mode] 01` | `05 5A 07 00 06 0E 00 0A <filter> [mode] 01` (or length 6 without `mode`) |
| Disable | `00 0B 01` | `05 5A 05 00 06 0E 00 0B 01` |
| Set pass-through gain | `00 0F <gain LE u16> 01` | `05 5A 07 00 06 0E 00 0F <lo> <hi> 01` |

Example: enable filter 1, mode 0:

```
05 5A 07 00 06 0E 00 0A 01 00 01
```

Response: the status is at `frame[6]`, the subcommand echo at `frame[7]`.

- success: `frame[6] == 0`;
- the `0x0E06` gain response contains the gain at offsets 8–9.

**`filter` values** (ANC/pass-through filter index):

| Value | Meaning |
|---|---|
| `0` | ANC off |
| `1`–`4` | ANC filters |
| `9`–`11` | pass-through filters |

**`mode` values** (`AirohaAncMode`):

| Value | Meaning |
|---|---|
| `0` | HYBRID |
| `1` | FF |
| `2` | FB |
| `4` | PASSTHROUGH |
| `6` | HYBRID_PASSTHROUGH |
| `7` | PASSTHROUGH_FB |
| `8` | ADAPTIVE_PASSTHROUGH |

For filters `9`–`11`, Flycc forces `mode = 4` (PASSTHROUGH); for filters `1`–`4`
it uses the ANC mode selected in the UI. For a simple ANC on/off switch in
Sonabud, use `filter = 1..4` with the firmware default mode, or
`filter = 0` for off. The exact mode depends on the model; the safest option is
to reproduce the configuration Flycc sends for the target device.

#### 5.2.2 Read state — `0x0901`

Request (MMI module 5 = ANC):

```
05 5A 04 00 01 09 05 00
```

Response:

```
05 5B 08 00 01 09 05 00 <result> <filter> <gainLo> <gainHi>
```

| Offset | Field | Meaning |
|---|---|---|
| 6–7 | module ID | `05 00` = ANC module |
| 8 | `result` | `0` = success |
| 9 | `filter` | current filter index (0 = off, 1–4 ANC, 9–11 pass-through) |
| 10–11 | `gain` | 16-bit LE gain, in hundredths (the SDK divides by 100) |

If the frame is not 12 bytes long, the SDK treats the gain as `100`.

#### 5.2.3 Legacy `0x1200` / `0x1201` / `0x1202`

| ID | Name | Payload | Status |
|---|---|---|---|
| `0x1200` | `RACE_ANC_ON` | `{01}` | not used by the 1562 SDK |
| `0x1201` | `RACE_ANC_OFF` | none | not used by the 1562 SDK |
| `0x1202` | `RACE_ANC_GET_STATUS` | not verified | class absent from the 1562 SDK |

These commands are documented because they appear in the 142-ID table. If the
target firmware does not answer `0x0E06`, they are a fallback lead, but their
response payload has not been observed here.

### 5.3 TWS relay — `0x0D01`

The relay sends a complete RACE frame to the other earbud.

**Request**

```
payload = [dstType][dstId] + complete inner RACE frame
```

- `dstType = 5` for AWSPEER;
- `dstId` comes from the `0x0D00` response;
- the inner frame keeps its own header, type, length and ID.

**Response**

```
payload = [addrType][addrId] + complete inner RACE frame
```

The 2 address bytes before the inner frame are not interpreted by the SDK. The
inner frame starts at `frame[8]`. The SDK checks that its type and ID match the
expected command, then reads its status.

**Prerequisite**: never send a relay if `0x0D00` did not return a
`type = 5` destination. The SDK would hit a null destination.

### 5.4 Case battery and charging

Two sources exist:

1. **Standard IDs `0x000B` / `0x0009`** (`RACE_BLUETOOTH_GET_BOX_BATTERY`,
   `RACE_BLUETOOTH_GET_CHARGING_INFO`). They appear in the table, but no
   implementation or payload was found in the 1562 SDK. Consider them
   **unconfirmed**.

2. **YFY vendor channel `0x2100`**, used by Flycc for its popup. This is not a
   `RaceId.java` ID. The firmware sends a notification whose first payload byte
   is `0x0D`:

```
05 5B/5D <len LE> 00 21 0D <event> <right> <left> <case> <earStatus>
```

| Offset | Field | Meaning |
|---|---|---|
| 6 | `0x0D` | “battery/status” subcommand |
| 7 | `event` | `0` GATT update, `5` advertisement, `6` popup close, `7` game mode, `8` ANC mode, `2` gyro (non-exhaustive) |
| 8 | `right` | right battery: bit 7 = charging, bits 0–6 = percentage |
| 9 | `left` | left battery, same encoding |
| 10 | `case` | case battery, same encoding |
| 11 | `earStatus` | earbud status (partially understood) |

The SDK does not filter the frame type for this packet; accept both `0x5B` and
`0x5D`. The original app also defines a “registration” `0x2100` frame whose
payload starts with `0x09` (the `MESSAGE_DEVICE` constant). The report
incorrectly mentions ID `0x0921`: the bytes `05 5A 09 00 00 21 …` actually
carry ID **`0x2100`**, not `0x0921`.

**Recommendation**: implement earbud battery through `0x0CD6` first. The case
battery may remain “unknown” until the target firmware is tested; if the
`0x2100/0x0D` channel is available, use it as a complement.

### 5.5 NVKEY read — `0x0A00`

Used for the chip name, device type and NVDM version.

**Request**

```
payload = [nvId LE u16][0x03E8 LE]
```

- `0x03E8` = 1000: maximum number of requested bytes.
- Example for `nvId = 0x1002`:

```
05 5A 06 00 00 0A 02 10 E8 03
```

**Response**

```
05 5B <len LE> 00 0A <dataLen LE> <data...>
```

- no status byte;
- `dataLen = frame[6..7]`;
- `data` starts at `frame[8]`.

NVKEY write commands (`0x0A01`, `0x0A03`, etc.) are **out of scope** for Buds
Companion: they modify persistent configuration and can make the device
unusable.

### 5.6 Firmware update (FOTA)

> Evidence: `com.airoha.libfota1568` (state machine, stages and packets),
> `YFY157xTwsFotaFragment` / `YFY1562TwsFotaFragment` and `AutoUpdater`,
> decompiled from Flycc 2.0.35 (`classes71.dex`). Static observation only: the
> update path was not run against real hardware. Anything not listed here is
> still unknown.

#### 5.6.1 Update discovery (vendor server)

Flycc uses plain HTTP and an unauthenticated file server:

| Target | Base URL |
|---|---|
| AB157x / AB1565 / AB1568 TWS | `http://twsfota.198509.xyz/tws_fota_bin/AB157x/` |
| AB1562 TWS | `http://twsfota.198509.xyz/tws_fota_bin/` |
| Single earbud / headset | `http://twsfota.198509.xyz/headset_fota_bin/` |

The path is `<Company>/<Module>/`, both read from the DFU information
(`0x1C07` / the dual query). Two Java-Properties XML files drive the check:

- `fota`: maps the current firmware version to a package directory;
- `update_info` (or `update_info_eng` outside CN): maps the package to release
  notes.

For AB157x the `fota` key is the current version string itself (for example
`1.2.3`). For AB1562 it is `<Company>_<Module>_V<version>`. The value has the
form `<name>_fota`; `<name>` is the package short name and the new version is
the `_V<version>` suffix.

The app compares the agent and partner versions by their last dot-separated
number and uses the **older** one as the lookup key, so both earbuds are updated
from the same package. The download is gated by a Wi-Fi check and a
confirmation dialog before the transfer starts.

#### 5.6.2 Package layout

AB157x uses one combined image for both earbuds:

```
.../fota_bin/AB157x/<Company>/<Module>/<name>_fota/<name>_fota.bin
```

AB1562 uses per-earbud images and optional file-system images:

```
.../tws_fota_bin/<Company>/<Module>/<name>_fota/<name>_left_FotaPackage.bin
.../tws_fota_bin/<Company>/<Module>/<name>_fota/<name>_right_FotaPackage.bin
.../tws_fota_bin/<Company>/<Module>/<name>_fota/<name>_filesystemimage.bin
.../tws_fota_bin/<Company>/<Module>/<name>_fota/<name>_filesystempackage.bin
```

The container header, compression and any whole-image checksum are **not
handled by the observed app code**. The only integrity data it computes is the
per-page CRC8 written during the flash stage. Package signing is unknown.

#### 5.6.3 DFU information

`0x1C07` get version:

```
request  : 05 5A 04 00 07 1C [role]          role 0 = agent, 1 = partner
response : 05 5D <len> 00 07 1C [status] [role] [versionLength] [ASCII version]
```

`0x1C00` (single) partition info:

```
request  : 05 5A 03 00 00 1C 00
response : 05 5B 0D 00 00 1C [status] [partitionId] [storageType] [start LE32] [length LE32]
```

`0x1C14` (TWS) query partition, request `05 5A 03 00 14 1C 00`:

```
05 5B 16 00 14 1C [status] [role] [storageType] [agentStart LE32] [agentLength LE32]
                [partnerRole] [partnerStart LE32] [partnerLength LE32]
```

#### 5.6.4 Transfer sequence

`0x1C08` start payload: `[target] [mode]`, where target is `1` for a single
earbud and `3` for TWS, and mode is the `FotaModeId`: `0` background,
`1` active, `2` adaptive.

Single earbud:

1. `0x1C00` partition info.
2. `0x1C08` start.
3. `0x1C1C` query transmit interval.
4. `0x0433` partition erase status.
5. `0x0431` compare partitions (SHA-256 diff).
6. `0x1C0A` start transaction.
7. `0x1C06` write state `0x0200`.
8. `0x0404` erase 4 KB blocks.
9. `0x1C06` write state `0x0201`, then `0x0210`.
10. `0x0402` program 256-byte pages (two per command by default).
11. `0x1C01` integrity check.
12. `0x1C06` write state `0x0211`.
13. `0x1C04` query state.
14. `0x1C02` commit, then wait up to 15 s for the reboot/disconnection.

TWS uses the same erase/program commands after `0x1C14` and `0x1C08`, with
`0x1C10` start transaction, `0x1C13` write state, `0x1C01` integrity check and
`0x1C11` TWS commit; it also uses `0x1C12` state query, `0x1C15` cancel,
`0x1C19` active-mode preparation and `0x1C1B` ping.

`0x0404` erase payload: `[storageType] [4096 LE32] [address LE32]`.

`0x0402` program payload:

```
[storageType] [pageCount]
  repeat pageCount:
    [crc8] [address LE32] [256 bytes data]
```

Only pages whose 4 KB chunk differs from the device SHA-256 are queued, and
all-`0xFF` pages are skipped. CRC8 is computed over the 256-byte page with
initial value `0`.

`0x1C01` integrity check payload: `[1] [recipient] [storageType]`.

`0x1C06` write state payload: `[state LE16]`, with `0x0200` before erase,
`0x0201` after erase, `0x0210` before program and `0x0211` after program. The
TWS `0x1C13` variant carries the state twice (4 bytes).

The state machine walks: Idle → AgentQuery → AgentFota → ConnectPartner →
PartnerQuery → PartnerFota → AgentCommit → Reconnection → Final, and finishes by
reading the versions again to confirm the update.

**Remaining unknowns**: the exact `0x0433`/`0x0431` payload variants, the role
byte semantics, the differences between background, active and adaptive mode,
the package container format, and any signature. Do not implement an unguarded
writer before these are validated on hardware.

The capture procedure that closes these gaps is described in
[`docs/fota-validation.md`](docs/fota-validation.md).

### 5.7 Other useful IDs

| ID | Name | Usage |
|---|---|---|
| `0x0CC4` | `RACE_GET_ROLE` | TWS role (init) |
| `0x0D00` | `RACE_RELAY_GET_AVA_DST` | available destinations / peer presence |
| `0x0CD3` | `RACE_BLUETOOTH_GET_CLIENT_EXISTENCE` | client existence (not used by the 1562 SDK) |
| `0x0CD4` | `RACE_BLUETOOTH_IS_AGENT_RIGHT_DEVICE` | agent side (not used) |
| `0x0CD7` | `RACE_BLUETOOTH_ROLE_SWITCH` | role switch (not used) |

---

## 6. Android implementation

### 6.1 Permissions

| API | Permissions |
|---|---|
| Android 12+ (API 31+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` |
| Android 6–11 (API 23–30) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` |

If scanning is not used to derive location, declare
`android:usesPermissionFlags="neverForLocation"` on `BLUETOOTH_SCAN` (API 31+).
Request runtime permissions before starting the scan.

### 6.2 Command queue

Implement a serialized queue:

- one request in flight;
- 1000 ms timeout;
- up to 3 attempts;
- match on `(id, type)`;
- `0x5D` indications are handled separately and must not consume the expected
  response.

A Kotlin `Channel`/`Mutex` or a lock-protected `ArrayDeque` is enough. Do not
send initialization and a battery read in parallel.

### 6.3 Frame parser

Kotlin pseudo-code:

```kotlin
fun feed(chunk: ByteArray) {
    buffer.write(chunk)
    while (buffer.size >= 4) {
        val length = buffer.peekU16Le(2)          // ID + payload
        val total = length + 4
        if (length == 0 || total < 6 || total > 2000) {
            buffer.drop(1)                        // resynchronize
            continue
        }
        if (buffer.size < total) return           // wait for more data
        val frame = buffer.read(total)
        dispatch(frame)
    }
}
```

`dispatch` must check `frame[0]` (`0x05`/`0x15`) and `frame[1]`
(`0x5B`/`0x5D`) before processing.

### 6.4 GATT writes

- Write to the **TX** characteristic, never to RX.
- Use `WRITE_TYPE_DEFAULT` (with response) for commands.
- Split the frame into `mtu - 3` byte chunks.
- Wait for `onCharacteristicWrite` before sending the next chunk.
- On Android 13+, use the
  `writeCharacteristic(characteristic, value, writeType)` overload; the older
  API is deprecated but still needed on older versions.

### 6.5 Reception

- Enable the RX CCCD before any command.
- On Android 13+, read the value through the
  `onCharacteristicChanged(gatt, characteristic, value)` overload.
- Copy the byte array immediately: some implementations reuse the buffer.
- Handle disconnection: clear the buffer, fail pending requests, then
  reinitialize on the next `onConnectionStateChange`.

### 6.6 Mapping to `BudsState`

| UI field | Source |
|---|---|
| left/right battery | `0x0CD6` local + relay |
| case battery | `0x2100/0x0D` when available, otherwise unknown |
| charging | bit 7 of the `0x2100/0x0D` battery bytes (or charging info when available) |
| ANC active | `0x0901` filter in `1..4` (filters `9..11` are pass-through) |
| ANC mode | current `filter` (`0x0901`) + `mode` sent with the last `0x0E06` |
| connection status | GATT state + peer presence from `0x0D00` |

Provide an “unknown” state for every value that was not read: do not display
`0%` by default, because it looks like an empty battery.

### 6.7 Known pitfalls

- **Do not confuse the RACE `0x05` with an H4 header**: over GATT, the frame
  starts directly with `05 5A`/`05 5B`.
- **The `length` field includes the ID**: `length = 2 + payload`.
- **NVKEY responses have no status**: the first payload byte is the data length.
- **`0x0901` has no status at `frame[6]`**: the module ID occupies
  `frame[6..7]` and the status is at `frame[8]`.
- **Relay shifts the inner frame to offset 8**: do not parse the inner ID at
  offset 4 of the outer frame.
- **An MTU smaller than 240 is possible**: always use the negotiated value, not
  a hard-coded 240.
- **Notifications may be fragmented**: never treat one notification as a
  complete frame.
- **Pairing may be required**: if the Airoha service does not appear, test
  Android bonding before concluding the device is incompatible.

---

## 7. Notes on the IDs

The table in Annex A contains **142 entries** from `RaceId.java`, but only
**132 distinct values**. Some values are aliases or naming collisions:

| Value | Names |
|---|---|
| `0x000E` (14) | `RACE_GET_DEVICE_COLOR_INDEX`, `RACE_JLAB_TOUCH_ACTIVATE` |
| `0x0403` (1027) | `RACE_STORAGE_PAGE_READ`, `RACE_STORAGE_READ_PAGE` |
| `0x0E06` (3590) | `DSP_REALTIME_ANC_ON`, `DSP_REALTIME_ANC_SET_RUNTIME_VOL` |
| `0x0E13` (3603) | `DSP_REALTIME_ANC_ADAPTIVE_CHECK`, `DSP_REALTIME_LEAKAGE_DETECTION` |
| `0x1101` (4353) | `RACE_KEYCODE_REBOOT`, `RACE_MMI_KEY_COMMAND` |
| `0x2200` (8704) | `RACE_LEAUDIO_EA_REPORT_IND`, `RACE_LEAUDIO_SCAN_BROADCAST_SOURCE` |
| `0x2201` (8705) | `RACE_LEAUDIO_PA_REPORT_IND`, `RACE_LEAUDIO_SELECT_BROADCAST_SOURCE` |
| `0x2202` (8706) | `RACE_LEAUDIO_BIS_SYNC_IND`, `RACE_LEAUDIO_PLAY_BIS` |
| `0x2203` (8707) | `RACE_LEAUDIO_BIS_TERMINATED_IND`, `RACE_LEAUDIO_STOP_BIS` |
| `0x2209` (8713) | `RACE_LEAUDIO_BIG_INFO_IND`, `RACE_LEAUDIO_SET_BROADCAST_CODE` |

Consequences:

- do not deduplicate the table without checking the context (command vs
  notification, LE Audio vs MMI);
- `0x0E06` is indeed the ANC command used by the SDK, despite its double name;
- `0x1101` is both “keycode reboot” and “MMI key command”: the payload decides
  the behavior, so never send it blindly.

---

## Annex A — 142 RACE IDs

Each row comes from `RaceId.java`. The “Note” column flags aliases and the
commands used in this document.

| Name | Dec | Hex | Note |
|---|---:|---|---|
| `DSP_ANC_USER_TRIGGER_RECORD_RESQUEST_QUERY` | 3606 | 0x0E16 |  |
| `DSP_AUDIO_FEATURE_CAPABILITY` | 3632 | 0x0E30 |  |
| `DSP_REALTIME_ANC_ADAPTIVE_CHECK` | 3603 | 0x0E13 | Alias/collision with `DSP_REALTIME_LEAKAGE_DETECTION` |
| `DSP_REALTIME_ANC_ADAPTIVE_RESULT` | 3604 | 0x0E14 |  |
| `DSP_REALTIME_ANC_GAIN_CONTROL` | 3607 | 0x0E17 |  |
| `DSP_REALTIME_ANC_NOTIFY_GAIN` | 3605 | 0x0E15 |  |
| `DSP_REALTIME_ANC_NOTIFY_GAIN_1562` | 3608 | 0x0E18 |  |
| `DSP_REALTIME_ANC_ON` | 3590 | 0x0E06 | ANC on/off/gain (SDK subcommands); Alias/collision with `DSP_REALTIME_ANC_SET_RUNTIME_VOL` |
| `DSP_REALTIME_ANC_SET_RUNTIME_VOL` | 3590 | 0x0E06 | ANC on/off/gain (SDK subcommands); Alias/collision with `DSP_REALTIME_ANC_ON` |
| `DSP_REALTIME_FULL_ADAPTIVE_ANC_MODE` | 3622 | 0x0E26 |  |
| `DSP_REALTIME_LEAKAGE_DETECTION` | 3603 | 0x0E13 | Alias/collision with `DSP_REALTIME_ANC_ADAPTIVE_CHECK` |
| `DSP_REALTIME_PEQ` | 3587 | 0x0E03 |  |
| `RACE_AIRDUMP_ONOFF` | 3595 | 0x0E0B |  |
| `RACE_ANCDUMP_ONOFF` | 3594 | 0x0E0A |  |
| `RACE_ANC_GET_STATUS` | 4610 | 0x1202 | Legacy ANC get status, not observed |
| `RACE_ANC_OFF` | 4609 | 0x1201 | Legacy ANC off, unused by the 1562 SDK |
| `RACE_ANC_ON` | 4608 | 0x1200 | Legacy ANC on, unused by the 1562 SDK |
| `RACE_ANC_READ_PARAM_FROM_NVKEY` | 4612 | 0x1204 |  |
| `RACE_ANC_SET_GAIN` | 4611 | 0x1203 |  |
| `RACE_ANC_WRITE_GAIN_TO_NVKEY` | 4613 | 0x1205 |  |
| `RACE_ANTENNAUT_REPORT_ENABLE` | 5888 | 0x1700 |  |
| `RACE_BLUETOOTH_GET_BOX_BATTERY` | 11 | 0x000B | Case battery, payload unconfirmed |
| `RACE_BLUETOOTH_GET_CHARGING_INFO` | 9 | 0x0009 | Charging info, payload unconfirmed |
| `RACE_BLUETOOTH_GET_CLIENT_EXISTENCE` | 3283 | 0x0CD3 | TWS client existence, unused |
| `RACE_BLUETOOTH_GET_IR_STATUS` | 12 | 0x000C |  |
| `RACE_BLUETOOTH_IS_AGENT_RIGHT_DEVICE` | 3284 | 0x0CD4 | Agent right side, unused |
| `RACE_BLUETOOTH_ROLE_SWITCH` | 3287 | 0x0CD7 | TWS role switch, unused |
| `RACE_BLUETOOTH_TWS_GET_BATTERY` | 3286 | 0x0CD6 | TWS battery (agent + relay) |
| `RACE_CODEC_A2DP_COEDC_INFO` | 3269 | 0x0CC5 |  |
| `RACE_DEVICE_OPERATION` | 11398 | 0x2C86 |  |
| `RACE_FIND_ME` | 11265 | 0x2C01 | Find my, payload not documented |
| `RACE_FIND_ME_QUERY_STATE` | 11264 | 0x2C00 | Find my query state, payload not documented |
| `RACE_FLASH_DUAL_DEVICES_PARTITION_ERASE` | 1807 | 0x070F |  |
| `RACE_FLASH_PAGE_PROGRAM` | 1799 | 0x0707 |  |
| `RACE_FLASH_SECTOR_ERASE` | 1796 | 0x0704 |  |
| `RACE_FLASH_SET_PROTECT_BIT` | 1794 | 0x0702 |  |
| `RACE_FOTA_ACTIVE_FOTA_PREPARATION` | 7193 | 0x1C19 |  |
| `RACE_FOTA_COMMIT` | 7170 | 0x1C02 |  |
| `RACE_FOTA_DUAL_DEVICES_CANCEL` | 7189 | 0x1C15 |  |
| `RACE_FOTA_DUAL_DEVICES_COMMIT` | 7185 | 0x1C11 |  |
| `RACE_FOTA_DUAL_DEVICES_QUERY_PARTITION_INFO` | 7188 | 0x1C14 |  |
| `RACE_FOTA_DUAL_DEVICES_QUERY_STATE` | 7186 | 0x1C12 |  |
| `RACE_FOTA_DUAL_DEVICES_START_TRANSACTION` | 7184 | 0x1C10 |  |
| `RACE_FOTA_DUAL_DEVICES_WRITE_STATE` | 7187 | 0x1C13 |  |
| `RACE_FOTA_GET_A2DP_CONNECTION_INFO` | 8 | 0x0008 |  |
| `RACE_FOTA_GET_AE_INFO` | 7177 | 0x1C09 |  |
| `RACE_FOTA_GET_INTERNAL_FLASH_PARTITION_SHA256` | 7183 | 0x1C0F |  |
| `RACE_FOTA_GET_PARTITION_ERASE_STATUS` | 7190 | 0x1C16 |  |
| `RACE_FOTA_GET_ROFS_VERSION` | 7426 | 0x1D02 |  |
| `RACE_FOTA_GET_VERSION` | 7175 | 0x1C07 |  |
| `RACE_FOTA_INTEGRITY_CHECK` | 7169 | 0x1C01 |  |
| `RACE_FOTA_PARTITION_INFO_QUERY` | 7168 | 0x1C00 |  |
| `RACE_FOTA_PING` | 7195 | 0x1C1B |  |
| `RACE_FOTA_QUERY_TRANSMIT_INTERVAL` | 7196 | 0x1C1C | Used by the TWS flow |
| `RACE_FOTA_QUERY_STATE` | 7172 | 0x1C04 |  |
| `RACE_FOTA_SET_IR_ONOFF` | 10 | 0x000A |  |
| `RACE_FOTA_SET_POWER_CONTROL_INFO` | 7 | 0x0007 |  |
| `RACE_FOTA_START` | 7176 | 0x1C08 |  |
| `RACE_FOTA_START_TRANSCATION` | 7178 | 0x1C0A |  |
| `RACE_FOTA_STOP` | 7171 | 0x1C03 |  |
| `RACE_FOTA_WRITE_STATE` | 7174 | 0x1C06 |  |
| `RACE_GET_A2DP_PARAMETER` | 3279 | 0x0CCF |  |
| `RACE_GET_ANC_SPORTS_MODE_STATUS` | 13 | 0x000D |  |
| `RACE_GET_BATTERY` | 3074 | 0x0C02 | Legacy battery, unused by the 1562 SDK |
| `RACE_GET_BOOT_REASON` | 7680 | 0x1E00 |  |
| `RACE_GET_BUILD_VERSION_INFO` | 7688 | 0x1E08 |  |
| `RACE_GET_DEVICE_COLOR_INDEX` | 14 | 0x000E | Alias/collision with `RACE_JLAB_TOUCH_ACTIVATE` |
| `RACE_GET_DUMP_ADDR` | 7682 | 0x1E02 |  |
| `RACE_GET_DUMP_REGION_INFO` | 7684 | 0x1E04 |  |
| `RACE_GET_EXCEPTION_LOG_ADDR` | 7686 | 0x1E06 |  |
| `RACE_GET_IN_EAR_ON_OFF` | 11281 | 0x2C11 |  |
| `RACE_GET_LE_LINK_STATUS` | 3282 | 0x0CD2 |  |
| `RACE_GET_MMI_COMMON_CONFIG` | 11395 | 0x2C83 |  |
| `RACE_GET_OFFLINE_LOG_INFO` | 7685 | 0x1E05 |  |
| `RACE_GET_POWER_MODE` | 524 | 0x020C |  |
| `RACE_GET_ROLE` | 3268 | 0x0CC4 | TWS role (init) |
| `RACE_GET_SDK_VERSION` | 769 | 0x0301 |  |
| `RACE_GET_SHARE_MODE` | 5121 | 0x1401 |  |
| `RACE_HA_CONFIG` | 11399 | 0x2C87 |  |
| `RACE_HOSTAUDIO_MMI_GET_ENUM` | 2305 | 0x0901 | MMI get enum, ANC state module 5 |
| `RACE_HOSTAUDIO_MMI_SET_ENUM` | 2304 | 0x0900 |  |
| `RACE_HOSTAUDIO_PEQ_SAVE_STATUS` | 2557 | 0x09FD |  |
| `RACE_JLAB_TOUCH_ACTIVATE` | 14 | 0x000E | Alias/collision with `RACE_GET_DEVICE_COLOR_INDEX` |
| `RACE_KEYCODE_REBOOT` | 4353 | 0x1101 | Reboot / MMI key command (payload-dependent); Alias/collision with `RACE_MMI_KEY_COMMAND` |
| `RACE_LEAUDIO_BIG_INFO_IND` | 8713 | 0x2209 | Alias/collision with `RACE_LEAUDIO_SET_BROADCAST_CODE` |
| `RACE_LEAUDIO_BIS_RESET` | 8708 | 0x2204 |  |
| `RACE_LEAUDIO_BIS_SYNC_IND` | 8706 | 0x2202 | Alias/collision with `RACE_LEAUDIO_PLAY_BIS` |
| `RACE_LEAUDIO_BIS_TERMINATED_IND` | 8707 | 0x2203 | Alias/collision with `RACE_LEAUDIO_STOP_BIS` |
| `RACE_LEAUDIO_EA_REPORT_IND` | 8704 | 0x2200 | Alias/collision with `RACE_LEAUDIO_SCAN_BROADCAST_SOURCE` |
| `RACE_LEAUDIO_GET_BIS_STATE` | 8712 | 0x2208 |  |
| `RACE_LEAUDIO_PA_REPORT_IND` | 8705 | 0x2201 | Alias/collision with `RACE_LEAUDIO_SELECT_BROADCAST_SOURCE` |
| `RACE_LEAUDIO_PLAY_BIS` | 8706 | 0x2202 | Alias/collision with `RACE_LEAUDIO_BIS_SYNC_IND` |
| `RACE_LEAUDIO_SCAN_BROADCAST_SOURCE` | 8704 | 0x2200 | Alias/collision with `RACE_LEAUDIO_EA_REPORT_IND` |
| `RACE_LEAUDIO_SELECT_BROADCAST_SOURCE` | 8705 | 0x2201 | Alias/collision with `RACE_LEAUDIO_PA_REPORT_IND` |
| `RACE_LEAUDIO_SET_BROADCAST_CODE` | 8713 | 0x2209 | Alias/collision with `RACE_LEAUDIO_BIG_INFO_IND` |
| `RACE_LEAUDIO_STOP_BIS` | 8707 | 0x2203 | Alias/collision with `RACE_LEAUDIO_BIS_TERMINATED_IND` |
| `RACE_MMI_KEY_COMMAND` | 4353 | 0x1101 | Reboot / MMI key command (payload-dependent); Alias/collision with `RACE_KEYCODE_REBOOT` |
| `RACE_NVKEY_READFULLKEY` | 2560 | 0x0A00 | NVKEY read (init) |
| `RACE_NVKEY_READFULLKEY_RETURN_NVID` | 2572 | 0x0A0C |  |
| `RACE_NVKEY_RECLAIM` | 2563 | 0x0A03 |  |
| `RACE_NVKEY_WRITEFULLKEY` | 2561 | 0x0A01 |  |
| `RACE_NVKEY_WRITEFULLKEY_RETURN_NVID` | 2573 | 0x0A0D |  |
| `RACE_OFFLINE_GET_LOG_TO_FLASH_SWITCH` | 7700 | 0x1E14 |  |
| `RACE_OFFLINE_SET_LOG_TO_FLASH_SWITCH` | 7701 | 0x1E15 |  |
| `RACE_ONLINELOG_RX_PACKAGE` | 65535 | 0xFFFF |  |
| `RACE_ONLINE_LOG_OVER_BT_START` | 7689 | 0x1E09 |  |
| `RACE_ONLINE_LOG_QUERY_CPU_FILTER_INFO` | 7692 | 0x1E0C |  |
| `RACE_ONLINE_LOG_QUERY_MODULE_INFO` | 7690 | 0x1E0A |  |
| `RACE_ONLINE_LOG_SAVE_CPU_FILTER` | 7694 | 0x1E0E |  |
| `RACE_ONLINE_LOG_SET_CPU_FILTER_INFO` | 7693 | 0x1E0D |  |
| `RACE_ONLINE_LOG_SET_MODULE_INFO` | 7691 | 0x1E0B |  |
| `RACE_ONLINE_LOG_TYPE_MSG_ID_LOG` | 3857 | 0x0F11 |  |
| `RACE_ONLINE_LOG_TYPE_TEXTURE_LOG` | 3856 | 0x0F10 |  |
| `RACE_READ_KEY_ACTION_NVKEY_FROM_RAM` | 2571 | 0x0A0B |  |
| `RACE_READ_SFR` | 513 | 0x0201 |  |
| `RACE_RELAY_GET_AVA_DST` | 3328 | 0x0D00 | Available destinations / TWS peer (init) |
| `RACE_RELAY_PASS_TO_DST` | 3329 | 0x0D01 | Relay to TWS peer |
| `RACE_RELOAD_NVKEY_TO_RAM` | 2569 | 0x0A09 |  |
| `RACE_RESUME_DSP` | 3586 | 0x0E02 |  |
| `RACE_SET_DEVICE_PUSH_STATUS_ONOFF` | 6 | 0x0006 |  |
| `RACE_SET_IN_EAR_ON_OFF` | 11280 | 0x2C10 |  |
| `RACE_SET_KEY_ACTION_NVKEY_TO_RAM` | 2570 | 0x0A0A |  |
| `RACE_SET_LE_CONNECTION_PARAM` | 3281 | 0x0CD1 |  |
| `RACE_SET_MMI_COMMON_CONFIG` | 11394 | 0x2C82 |  |
| `RACE_SOFTWARE_RESET` | 7173 | 0x1C05 |  |
| `RACE_SPIFLASH_DUAL_DEVICES_PARTITION_ERASE` | 1043 | 0x0413 |  |
| `RACE_STORAGE_BYTE_PROGRAM` | 1024 | 0x0400 |  |
| `RACE_STORAGE_DUAL_DEVICES_PARTITION_ERASE` | 1074 | 0x0432 |  |
| `RACE_STORAGE_EMPTY_KE` | 1264 | 0x04F0 |  |
| `RACE_STORAGE_GET_4K_ERASED_STATUS` | 1075 | 0x0433 |  |
| `RACE_STORAGE_GET_PARTITION_SHA256` | 1073 | 0x0431 |  |
| `RACE_STORAGE_LOCK_UNLOCK` | 1072 | 0x0430 |  |
| `RACE_STORAGE_PAGE_PROGRAM` | 1026 | 0x0402 |  |
| `RACE_STORAGE_PAGE_READ` | 1027 | 0x0403 | Alias/collision with `RACE_STORAGE_READ_PAGE` |
| `RACE_STORAGE_PARTITION_ERASE` | 1028 | 0x0404 |  |
| `RACE_STORAGE_READ_MANUFACTURER_AND_MEMORYTYPE` | 1184 | 0x04A0 |  |
| `RACE_STORAGE_READ_OTP` | 1186 | 0x04A2 |  |
| `RACE_STORAGE_READ_PAGE` | 1027 | 0x0403 | Alias/collision with `RACE_STORAGE_PAGE_READ` |
| `RACE_STORAGE_SET_CONFIGURATION_REGISTER` | 1185 | 0x04A1 |  |
| `RACE_SUSPEND_DSP` | 3585 | 0x0E01 |  |
| `RACE_SWITCH_POWER_MODE` | 525 | 0x020D |  |
| `RACE_TOOL_ASSERT` | 7687 | 0x1E07 |  |
| `RACE_TRIGGER_LOG_DUMP` | 7683 | 0x1E03 |  |

---

## Annex B — Hex summary

| Action | TX frame |
|---|---|
| Init — chip name (NVKEY 4098) | `05 5A 06 00 00 0A 02 10 E8 03` |
| Init — type (NVKEY 0x183D) | `05 5A 06 00 00 0A 3D 18 E8 03` |
| Init — type (NVKEY 0xF2B0) | `05 5A 06 00 00 0A B0 F2 E8 03` |
| Init — NVDM version (NVKEY 4097) | `05 5A 06 00 00 0A 01 10 E8 03` |
| Init — role | `05 5A 02 00 C4 0C` |
| Init — destinations | `05 5A 02 00 00 0D` |
| Agent battery | `05 5A 03 00 D6 0C 00` |
| Partner battery (relay, peer 0x01) | `05 5A 0B 00 01 0D 05 01 05 5A 03 00 D6 0C 00` |
| ANC on — filter 1, mode 0 | `05 5A 07 00 06 0E 00 0A 01 00 01` |
| ANC off | `05 5A 05 00 06 0E 00 0B 01` |
| ANC gain — 0x0064 (100) | `05 5A 07 00 06 0E 00 0F 64 00 01` |
| ANC state (module 5) | `05 5A 04 00 01 09 05 00` |

---

## Annex C — Sources, confidence and limits

| Item | Source | Confidence |
|---|---|---|
| GATT/SPP UUIDs, MTU 240 | `UuidTable.java`, `GattLinkParam.java` | high |
| Frame, head/type/length/ID | `RacePacket.java`, `RaceType.java` | high |
| Init NVKEY 4098/4097, type, role, destinations | `CommonStage*`, `AirohaCommonControl.java` | high (static) |
| `0x183D`/`0xF2B0` selection | `AirohaCommonMgr.getDeviceType` | high (static) |
| Battery `0x0CD6` | `MmiStageTwsGetBattery` | high for the frame, level semantics inferred |
| Relay `0x0D01` | `RaceCmdRelayPass`, `RelayRespExtracter` | high |
| ANC `0x0E06` / `0x0901` | `MmiStageAncOn/Off`, `MmiStageGetAncStatus` | high (static), not tested on hardware |
| `0x1200`/`0x1201` | `RaceCmdAncOn/Off` | presence certain, usage not observed |
| Case `0x000B`/`0x0009` | `RaceId.java` only | unconfirmed |
| Notification `0x2100/0x0D` | `SettingFragment`, `AirohaMmiMgr` | high in Flycc, depends on YFY firmware |
| FOTA `0x1C00`–`0x1C1C`, `0x0402`, `0x0404`, `0x0431`, `0x0433` | `AirohaLeaFotaMgr`, `libfota1568` stages | high for the command sequence; package container and signing unknown |

**Limits**:

- static analysis: no real exchange was captured to validate the payloads on an
  AB1565;
- the ANC `mode` values and the exact battery-level semantics must be verified
  on the target device;
- firmware may vary by model and version; always provide a degraded mode and an
  “unknown” state in the UI.

---

*Document maintained for Sonabud. See also
[`REPORT-communication.md`](REPORT-communication.md) for the full packer, SDK
and CVE analysis.*


