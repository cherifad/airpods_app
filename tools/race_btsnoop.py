#!/usr/bin/env python3
"""Decode RACE frames from an Android Bluetooth HCI snoop log.

Pure standard library; no tshark needed.

Usage:
    python3 tools/race_btsnoop.py btsnoop_hci.log
    python3 tools/race_btsnoop.py btsnoop_hci.log --handle 0x0011 --summary

The script understands the btsnoop container (H4 or H1 datalink), extracts ATT
writes/notifications for every connection handle, reassembles each direction,
and prints complete RACE frames (PROTOCOL.md §3).
"""

from __future__ import annotations

import argparse
import datetime as dt
import struct
from collections import Counter, defaultdict

BTSNOOP_MAGIC = b"btsnoop\x00"
UNIX_EPOCH_OFFSET_S = 62_168_256_000  # seconds between year 0 and 1970

ATT_CID = 0x0004
ATT_WRITE_REQ = 0x12
ATT_WRITE_CMD = 0x52
ATT_NOTIFY = 0x1B
ATT_INDICATE = 0x1D

RACE_HEADS = {0x05, 0x15}
RACE_TYPES = {0x5A, 0x5B, 0x5C, 0x5D}
MAX_RACE_FRAME = 2000


def parse_btsnoop(data: bytes):
    if len(data) < 16 or not data.startswith(BTSNOOP_MAGIC):
        raise ValueError("not a btsnoop file")
    version, datalink = struct.unpack_from(">II", data, 8)
    offset = 16
    while offset + 24 <= len(data):
        orig_len, incl_len, flags, _drops, ts = struct.unpack_from(">IIIIQ", data, offset)
        offset += 24
        payload = data[offset : offset + incl_len]
        offset += incl_len
        if len(payload) != incl_len:
            break
        yield version, datalink, flags, ts, payload


def hci_acl(payload: bytes, datalink: int):
    if datalink == 1002:  # H4: one packet type byte
        if not payload or payload[0] != 0x02:
            return None
        payload = payload[1:]
    elif not payload:
        return None
    if len(payload) < 4:
        return None
    handle_flags, dlen = struct.unpack_from("<HH", payload, 0)
    body = payload[4 : 4 + dlen]
    if len(body) != dlen:
        return None
    return handle_flags & 0x0FFF, body


def att_value(body: bytes):
    if len(body) < 4:
        return None
    _l2len, cid = struct.unpack_from("<HH", body, 0)
    if cid != ATT_CID:
        return None
    att = body[4:]
    if not att:
        return None
    opcode = att[0]
    if opcode in (ATT_WRITE_REQ, ATT_WRITE_CMD, ATT_NOTIFY, ATT_INDICATE):
        if len(att) < 3:
            return None
        handle = struct.unpack_from("<H", att, 1)[0]
        return handle, att[3:]
    return None


def race_frames(stream: bytes):
    """Yield (frame_bytes, raw_hex) for each complete RACE frame."""
    i = 0
    while i + 4 <= len(stream):
        if stream[i] not in RACE_HEADS or stream[i + 1] not in RACE_TYPES:
            i += 1
            continue
        length = stream[i + 2] | (stream[i + 3] << 8)
        total = length + 4
        if length < 2 or total < 6 or total > MAX_RACE_FRAME:
            i += 1
            continue
        if i + total > len(stream):
            return
        yield stream[i : i + total]
        i += total


def hexdump(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", help="btsnoop_hci.log")
    parser.add_argument("--handle", type=lambda s: int(s, 0), help="only this ATT handle")
    parser.add_argument("--direction", choices=("tx", "rx"), help="host->device or device->host")
    parser.add_argument("--summary", action="store_true", help="count frames per command instead of listing")
    args = parser.parse_args()

    buffers = defaultdict(bytearray)
    counts = Counter()
    for _version, datalink, flags, ts, payload in parse_btsnoop(open(args.log, "rb").read()):
        acl = hci_acl(payload, datalink)
        if acl is None:
            continue
        handle, body = acl
        if args.handle is not None and handle != args.handle:
            continue
        direction = "rx" if flags & 0x01 else "tx"  # 0 = sent by host
        if args.direction and direction != args.direction:
            continue
        value = att_value(body)
        if value is None:
            continue
        att_handle, chunk = value
        key = (handle, att_handle, direction)
        buffers[key].extend(chunk)
        stream = buffers[key]
        consumed = 0
        for frame in race_frames(bytes(stream)):
            command = struct.unpack_from("<H", frame, 4)[0]
            counts[(direction, command)] += 1
            if not args.summary:
                when = "-"
                if ts:
                    try:
                        when = f"{dt.datetime.utcfromtimestamp(ts / 1e6 - UNIX_EPOCH_OFFSET_S):%H:%M:%S.%f}"
                    except (OverflowError, OSError, ValueError):
                        pass
                print(
                    f"{when:>15} {direction} hci=0x{handle:03X} att=0x{att_handle:04X} "
                    f"cmd=0x{command:04X} {hexdump(frame)}"
                )
            consumed += len(frame)
        if consumed:
            del stream[:consumed]

    if args.summary:
        for (direction, command), count in sorted(counts.items()):
            print(f"{direction} 0x{command:04X}  {count}")
    if not counts:
        print("no complete RACE frames found")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
