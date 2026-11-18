"""Run the opt-in Android Rust video recovery probe and decode recovered AUs in memory."""
import argparse
import os
import pathlib
import shutil
import socket
import struct
import subprocess
import threading
import time


def decoder(listener, ffmpeg):
    while True:
        try:
            client, _ = listener.accept()
        except OSError:
            return
        with client:
            try:
                client.settimeout(3)
                with client.makefile("rb") as stream:
                    codec, size = struct.unpack(">BI", stream.read(5))
                    if size > 4 * 1024 * 1024 or codec > 1:
                        continue
                    au = stream.read(size)
                    if len(au) != size:
                        continue
                proc = subprocess.run(
                    [ffmpeg, "-v", "error", "-xerror", "-err_detect", "explode",
                     "-f", ("h264", "hevc")[codec], "-i", "pipe:0", "-frames:v", "1",
                     "-f", "framehash", "pipe:1"], input=au, capture_output=True, timeout=2)
                frames = sum(line.startswith(b"0,") for line in proc.stdout.splitlines())
                ok = proc.returncode == 0 and frames == 1
                missing_pps = b"non-existing PPS" in proc.stderr
                print(f"HOST decoder frames={frames} rc={proc.returncode} missing_pps={missing_pps}", flush=True)
                client.sendall(bytes([ok]))
            except (OSError, struct.error, subprocess.TimeoutExpired) as error:
                print(f"HOST decoder error: {error}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=("natural", "inject-first", "inject-two"), default="natural")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--declared-only", action="store_true", help="exercise the second, declared-size round")
    parser.add_argument("--keyframe-seconds", type=int, choices=range(1, 11), default=3)
    parser.add_argument("--binary", default="/data/local/tmp/hc-video-recovery")
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    adb = [shutil.which("adb") or str(pathlib.Path(sdk, "platform-tools", "adb.exe")), "-s", args.serial]
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        parser.error("ffmpeg is required on PATH")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        host = listener.getsockname()[1]
        port = int(subprocess.check_output(adb + ["reverse", "tcp:0", f"tcp:{host}"], text=True).strip())
        threading.Thread(target=decoder, args=(listener, ffmpeg), daemon=True).start()
        try:
            for attempt in range(1, args.runs + 1):
                for _ in range(20):
                    if not subprocess.run(adb + ["shell", "pidof", "com.baidu.carlife.xiaomi"],
                                          capture_output=True).stdout.strip():
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError("CarLife still running; cold start unavailable")
                print(f"RUN {attempt} mode={args.mode}", flush=True)
                cmd = adb + ["shell", "env", f"HC_PROBE_VIDEO_RECOVERY={args.mode}",
                             f"HC_PROBE_DECODER_PORT={port}", f"HC_PROBE_KEYFRAME_SECONDS={args.keyframe_seconds}"]
                if args.declared_only:
                    cmd.append("HC_PROBE_DECLARED_ONLY=1")
                cmd += [args.binary, "--ignored",
                             "live_carwith_late_display", "--nocapture"]
                with subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                      text=True, encoding="utf-8", errors="replace") as proc:
                    observed = False
                    for line in proc.stdout:
                        print(line, end="", flush=True)
                        if "SOURCE-PROBE start_epoch_ms=" in line:
                            subprocess.run(adb + ["shell", "am", "start", "-W", "-n",
                                           "com.miui.carlink/com.miui.carlink.castfwk.usb.CarlifeAdbActivity"],
                                           stdout=subprocess.DEVNULL, check=True, timeout=12)
                        observed |= "RECOVERY dropped first" in line
                    print(f"END {attempt} test_exit={proc.wait()} observed={observed}", flush=True)
                if observed:
                    break
        finally:
            subprocess.run(adb + ["reverse", "--remove", f"tcp:{port}"], check=True)


if __name__ == "__main__":
    main()
