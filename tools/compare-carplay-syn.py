"""Compare initial TCP SYNs in a Wi-Fi capture with a HyperCar click trace."""

import argparse
from decimal import Decimal
from ipaddress import ip_address
from pathlib import Path
import re
import subprocess


ANCHOR = re.compile(r"CTRL-CLICK: marker=(\d+) epochMs=(\d+) elapsedMs=\d+\s*$")
EVENT = re.compile(r"CTRL-CLICK: marker=(\d+) deltaMs=(-?\d+) epochMs=(\d+) elapsedMs=\d+ (.*)")
FIELDS = ("frame.time_epoch", "ip.src", "ipv6.src", "ip.dst", "ipv6.dst", "tcp.dstport")


def endpoint_events(lines, marker, anchor):
    events = {}
    for line in lines:
        match = EVENT.search(line)
        if not match or int(match[1]) != marker or abs(int(match[3]) - anchor - int(match[2])) > 1000:
            continue
        fields = dict(re.findall(r"\b(event|attempt|currentPort|closedPort)=(\S+)", match[4]))
        kind = fields.get("event")
        if kind not in ("listener-open", "listener-close", "click-state"):
            continue
        port = fields.get("closedPort" if kind == "listener-close" else "currentPort", "none")
        events[(int(match[3]), kind, int(port) if port.isdecimal() else None)] = None
    return sorted(events, key=lambda item: item[0])


def compare(rows, anchor, events, car_ips, phone_ips, old_ports):
    history = iter(events)
    change = next(history, None)
    current = None
    old = set(old_ports)
    for row in rows:
        stamp, src4, src6, dst4, dst6, dest = row.split("\t")
        source, target = src4 or src6, dst4 or dst6
        if not source or not target or ip_address(source) not in car_ips or ip_address(target) not in phone_ips:
            continue
        at = round(Decimal(stamp) * 1000)
        if not -15_000 <= at - anchor <= 30_000:
            continue
        while change is not None and change[0] <= at:
            _, kind, port = change
            if kind == "listener-close":
                old.add(port)
                if current == port:
                    current = None
            else:
                if current is not None and current != port:
                    old.add(current)
                current = port
            change = next(history, None)
        port = int(dest)
        result = "current-port" if port == current else "old-port" if port in old else "unknown-port"
        yield f"epochMs={at} deltaMs={at - anchor:+} {source} -> [{target}]:{port} {result} currentPort={current}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="HyperCar log containing a click marker")
    parser.add_argument("capture", type=Path, help="Wi-Fi pcap or pcapng with decrypted IP traffic")
    parser.add_argument("--car", required=True, action="append", type=ip_address, help="Car source IP; repeat for IPv6")
    parser.add_argument("--phone", required=True, action="append", type=ip_address, help="Phone IP; repeat for IPv6 or an old address")
    parser.add_argument("--old-port", action="append", type=int, default=[], help="Known previous CarPlay control port from earlier logs")
    parser.add_argument("--tshark", default="tshark", help="Path to the Wireshark command-line decoder")
    args = parser.parse_args()

    lines = args.log.read_text(encoding="utf-8-sig").splitlines()
    anchors = [(int(match[2]), int(match[1])) for line in lines if (match := ANCHOR.search(line))]
    if not anchors:
        parser.error("No CTRL-CLICK clock anchor in the log")
    anchor, marker = max(anchors)
    events = endpoint_events(lines, marker, anchor)
    cmd = [args.tshark, "-r", str(args.capture), "-Y", "tcp.flags.syn == 1 && tcp.flags.ack == 0",
           "-T", "fields", "-E", "occurrence=f"]
    for field in FIELDS:
        cmd.extend(("-e", field))
    try:
        result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    except FileNotFoundError:
        parser.error("tshark not found; pass --tshark with the executable path")
    except subprocess.CalledProcessError as error:
        parser.error(error.stderr.strip() or "tshark could not read the capture")
    all_rows = sorted(result.stdout.splitlines(), key=lambda row: Decimal(row.partition("\t")[0]))
    print(f"marker={marker} epochMs={anchor}")
    matches = list(compare(all_rows, anchor, events, args.car, args.phone, args.old_port))
    print("\n".join(matches) if matches else "No matching SYN in the supplied capture and time window.")


if __name__ == "__main__":
    main()
