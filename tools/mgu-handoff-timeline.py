"""Read a HyperCar phone log and correlate MGU screen and Wi-Fi iAP2 startup."""

import argparse
from dataclasses import dataclass, field
from pathlib import Path
import re


@dataclass
class Event:
    line: int
    time: str
    message: str

    def millis(self):
        hour, minute, second, ms = map(int, re.split(r"[:.]", self.time))
        return ((hour * 60 + minute) * 60 + second) * 1000 + ms

    def since(self, other):
        delta = self.millis() - other.millis()
        if self.line > other.line and delta < -43_200_000:
            return delta + 86_400_000
        if self.line < other.line and delta > 43_200_000:
            return delta - 86_400_000
        return delta


@dataclass
class Session:
    start: Event
    control: str
    mgu: bool = False
    events: list = field(default_factory=list)
    wifi_ids: set = field(default_factory=set)
    wifi_starts: set = field(default_factory=set)
    event_channels: set = field(default_factory=set)


def sessions(lines):
    current = None
    for number, line in enumerate(lines, 1):
        if line.strip() == "===== HyperCar 启动 =====":
            if current:
                yield current
            current = None
        match = re.match(r"^(\d\d:\d\d:\d\d\.\d{3})\s+(.+)", line)
        if not match:
            continue
        event = Event(number, *match.groups())
        msg = event.message
        start = re.match(r"AIRPLAY: 控制连接 RTSP#(\d+) 开始", msg)
        if start:
            if current:
                yield current
            current = Session(event, start[1])
            continue
        if not current:
            continue
        if msg.startswith("SESSION-CLOSE: 引擎清理") or re.match(r"CORE .*引擎配置", msg):
            yield current
            current = None
            continue
        if msg.startswith(("BMW-MGU:", "AUDIO-CAPS: receiver=MGU")):
            current.mgu = True
        channel = re.match(r"AIRPLAY: 事件连接 RTSP#(\d+) 建立", msg)
        if channel:
            current.event_channels.add(channel[1])
        wifi = re.match(r"WIFI-IAP#(\d+): ", msg)
        if wifi:
            current.wifi_ids.add(wifi[1])
            if re.match(r"(?:WAIT_PEER elapsed=0ms\b|LINK peer bytes=|phase=WAIT_PEER$)", msg[wifi.end():]):
                current.wifi_starts.add(wifi[1])
        if msg.startswith(("WIFI-IAP#", "RTSP#", "VIDEO: IDR TCP written", "VIDEO: sender released",
                           "AIRPLAY: 主屏 TCP 就绪", "CARWITH-SOURCE:")):
            current.events.append(event)
    if current:
        yield current


def describe(session):
    ctrl = session.control
    rows = []
    posts = {}
    replies = {}
    last_ack = None
    sent_messages = {}
    control_eof = None
    ready_count = 0
    first_idr = None
    saw_replay = False
    first_ready = None
    trace_limited = False
    notes = []
    wifi = next(iter(session.wifi_starts)) if len(session.wifi_starts) == 1 else None
    for e in session.events:
        msg = e.message
        ready = re.match(r"AIRPLAY: 主屏 TCP 就绪 .*?connId=(\d+)", msg)
        if ready:
            ready_count += 1
            if first_ready is None:
                first_ready = e
            rows.append((e, f"视频 TCP 就绪 #{ready_count} connId={ready[1]}"))
        if re.match(r"CARWITH-SOURCE: owner=\d+ screen ready; replay current bounded GOP", msg):
            saw_replay = True
            rows.append((e, "发送器已安装，触发 GOP 回放"))
        idr = re.match(r"VIDEO: IDR TCP written .*?frame=(\d+) wire=(\d+)B", msg)
        if idr and (first_idr is None or idr[1] == "1"):
            label = "首次观察到 IDR 写完" if first_idr is None else "重建发送器的首个 IDR 写完"
            first_idr = first_idr or e
            delay = f"，距视频 TCP 就绪 {e.since(first_ready)} ms" if ready_count == 1 else ""
            source = re.search(r"\borigin=(cached|live|unknown)\b", msg)
            origins = {"cached": "缓存回放", "live": "新到源帧", "unknown": "来源未确认"}
            origin = origins[source[1]] if source else "来源未记录"
            rows.append((e, f"{label} frame={idr[1]} wire={idr[2]}B，{origin}{delay}"))
        released = re.match(r"VIDEO: sender released .*?tcp_written_bytes=(\d+).*?written_idrs=(\d+)", msg)
        if released:
            rows.append((e, f"发送器释放，累计写出 {released[1]}B / {released[2]} 个 IDR"))
        response = re.match(rf"RTSP#{ctrl} async RX CSeq=(\d+) status=(\d+)", msg)
        if response:
            replies[response[1]] = (e, response[2])
        eof = re.match(r"RTSP#(\d+): 对端 EOF", msg)
        if eof and eof[1] == ctrl and control_eof is None:
            control_eof = e
            rows.append((e, f"控制 RTSP#{ctrl} 对端 EOF"))
        elif eof and eof[1] in session.event_channels:
            rows.append((e, f"事件 RTSP#{eof[1]} 对端 EOF"))
        if not wifi or not msg.startswith(f"WIFI-IAP#{wifi}: "):
            continue
        post = re.search(r"control POST written CSeq=(\d+) seq=(\d+) ack=\d+ session=\d+ csm=(0x[\da-fA-F]+)\b", msg)
        if post:
            sent_messages.setdefault(post[2], set()).add(post[3])
            if post[3] == "0x4171":
                posts[post[1]] = e
                rows.append((e, f"4171 POST 写完 seq={post[2]} CSeq={post[1]}"))
        rx = re.search(r"LINK RX ctl=0x([\da-fA-F]+) seq=(\d+) ack=(\d+)", msg)
        if rx and int(rx[1], 16) & 0x40 and control_eof is None:
            last_ack = (e, f"最后记录的车机入站 ACK={rx[3]}，车机 seq={rx[2]}")
            # Only label startup packets before the bounded link trace rolls over or restarts.
            matches = sent_messages.get(rx[3], set())
            if not trace_limited and len(matches) == 1:
                last_ack = (e, last_ack[1] + f"，对应手机已写 {next(iter(matches))}")
        if "LINK DETECT restart" in msg:
            sent_messages.clear()
        if "LINK trace limit reached" in msg:
            trace_limited = True
        if ": END phase=" in msg:
            ack = re.search(r"\brx_seq=(\d+) rx_ack=(\d+)", msg)
            if ack:
                rows.append((e, f"Wi-Fi 结束快照 rx_ack={ack[2]} rx_seq={ack[1]}（采样结束时间）"))

    # The reader can log the reply before the writer logs its completed POST.
    for cseq in posts:
        if cseq in replies:
            e, status = replies[cseq]
            detail = f"4171 外层 RTSP {status}，控制 RTSP#{ctrl} CSeq={cseq}"
            if first_idr:
                detail += f"，相对首个 IDR 写完 {e.since(first_idr):+d} ms"
            rows.append((e, detail))
        else:
            notes.append(f"未观察到 4171 CSeq={cseq} 的控制通道应答")
    if last_ack:
        rows.append(last_ack)
    if len(session.wifi_starts) > 1:
        notes.append("出现多个 Wi-Fi iAP2 起点，省略 ACK 和 4171 关联")
    elif session.wifi_ids and not wifi:
        notes.append("缺少本次 Wi-Fi iAP2 起点，省略 ACK 和 4171 关联")
    elif not posts:
        notes.append("未观察到带 4171 消息号的 POST 写完记录")
    if not last_ack:
        notes.append("未观察到可归属的车机入站 ACK")
    if trace_limited:
        notes.append("逐帧日志已达上限，以 END 核对最终值，不能用 END 时间替代收包时间")
    if not ready_count:
        notes.append("未观察到视频 TCP 就绪")
    if not saw_replay:
        notes.append("未观察到发送器安装后的 GOP 回放标记")
    if not first_idr:
        notes.append("未观察到 IDR 写完")
    if not control_eof:
        notes.append("未观察到控制 EOF")
    elif first_idr:
        notes.append(f"控制 EOF 相对首个 IDR 写完 {control_eof.since(first_idr):+d} ms")
    return sorted(rows, key=lambda row: row[0].line), notes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="手机导出的 HyperCar 文本日志")
    parser.add_argument("--start", help="按控制连接开始时间前缀选择，如 18:11:04，可用于其他接收端对照")
    args = parser.parse_args()
    count = 0
    with args.log.open(encoding="utf-8-sig") as stream:
        for session in sessions(stream):
            if args.start is not None:
                if not session.start.time.startswith(args.start):
                    continue
            elif not session.mgu:
                continue
            count += 1
            print(f"\n{session.start.time}  L{session.start.line}  控制 RTSP#{session.control}  "
                  f"Wi-Fi iAP2={','.join(sorted(session.wifi_starts)) or '起点未观察到'}")
            rows, notes = describe(session)
            for e, detail in rows:
                print(f"{e.time}  {e.since(session.start):+7d} ms  L{e.line:<6} {detail}")
            for note in notes:
                print(f"  · {note}")
    if not count:
        parser.exit(1, "没有匹配的控制会话起点。可用 --start 指定导出中的开始时间。\n")


if __name__ == "__main__":
    main()
