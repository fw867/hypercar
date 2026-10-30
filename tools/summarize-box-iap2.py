"""Summarize boxed iAP2 logs without exporting credentials or message bodies.

Reads vendor text dumps; does not execute or import anything from the input.
Truncated dumps remain explicitly truncated and are never padded or replayed.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


RECORD = re.compile(
    r"\[([\d.]+)\].*?(rev|send) \((bt|wlan)\) (\d+) iap data"
)
HEX = re.compile(r"\b([0-9A-Fa-f]{4}): ((?:[0-9A-Fa-f]{2}(?: |$))+)" )
MARKER = bytes.fromhex("ff550200ee10")


def summarize(path: Path) -> list[dict]:
    lines = path.read_text(encoding="utf-8-sig", errors="replace").splitlines()
    result = []
    for index, line in enumerate(lines):
        record = RECORD.search(line)
        if record is None:
            continue
        stamp, direction, transport, declared = record.groups()
        raw = bytearray()
        cursor = index + 1
        while cursor < len(lines):
            chunk = HEX.search(lines[cursor])
            if chunk is None or int(chunk[1], 16) != len(raw):
                break
            raw.extend(bytes.fromhex(chunk[2]))
            cursor += 1
        raw = raw[:int(declared)]
        common = {
            "seconds": float(stamp), "line": index + 1,
            "direction": "box_to_car" if direction == "send" else "car_to_box",
            "transport": transport,
        }
        offset = 0
        while offset < len(raw):
            data = raw[offset:]
            if data.startswith(MARKER):
                result.append({**common, "kind": "detect", "captured": 6})
                offset += 6
                continue
            if len(data) < 9 or data[:2] != b"\xff\x5a":
                result.append({**common, "kind": "unparsed", "captured": len(data)})
                break
            length = int.from_bytes(data[2:4], "big")
            if length < 9:
                result.append({**common, "kind": "invalid_length", "wire_length": length})
                break
            packet = data[:length]
            control = packet[4]
            item = {
                **common,
                "kind": "syn" if control & 0x80 else "ack",
                "control": f"0x{control:02x}",
                "seq": packet[5], "ack": packet[6], "session": packet[7],
                "wire_length": length, "captured": len(packet),
                "truncated": len(packet) < length,
            }
            # CSM header alone identifies a message without exposing its TLVs.
            if not control & 0x80 and packet[7] and len(packet) >= 15 and packet[9:11] == b"@@":
                item["kind"] = "csm"
                item["message"] = f"0x{int.from_bytes(packet[13:15], 'big'):04x}"
            if control & 0x80 and len(packet) >= 19:
                sync = packet[9:]
                item["sync"] = {
                    "version": sync[0], "window": sync[1],
                    "max_length": int.from_bytes(sync[2:4], "big"),
                    "retry_ms": int.from_bytes(sync[4:6], "big"),
                    "ack_ms": int.from_bytes(sync[6:8], "big"),
                    "max_retries": sync[8], "max_acks": sync[9],
                }
                # The final payload checksum is not a session descriptor.
                payload_end = min(len(packet), length - 1)
                item["sync"]["sessions"] = [
                    {"id": packet[p], "kind": packet[p + 1], "version": packet[p + 2]}
                    for p in range(19, payload_end - 2, 3)
                ]
            result.append(item)
            if len(packet) < length:
                break
            offset += length
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = [{"file": path.name, "records": summarize(path)} for path in args.logs]
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    for sample in report:
        entries = sample["records"]
        wlan = [x for x in entries if x["transport"] == "wlan"]
        print(f"{sample['file']}: {len(entries)} packets, {len(wlan)} WLAN, "
              f"{sum(x.get('truncated', False) for x in entries)} truncated")


if __name__ == "__main__":
    main()
