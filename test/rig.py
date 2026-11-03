#!/usr/bin/env python3
"""Run vcam and an Android emulator together for interactive Fudge testing."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT.parent.parent


def approval_button(dump, app_name, device_pattern):
    """Find the Allow button only in the expected companion-device dialog."""
    start = dump.find("<?xml")
    if start < 0:
        start = dump.find("<hierarchy")
    if start < 0:
        return None
    end = dump.rfind("</hierarchy>")
    if end < 0:
        return None
    try:
        root = ET.fromstring(dump[start:end + len("</hierarchy>")])
    except ET.ParseError:
        return None
    nodes = [node for node in root.iter("node")
             if node.get("package", "").endswith(".companiondevicemanager")]
    if not nodes:
        return None
    text = " ".join(node.get("text", "") for node in nodes)
    if app_name not in text or not device_pattern.search(text):
        return None
    for node in nodes:
        if (node.get("text") == "Allow" and node.get("enabled") == "true"
                and node.get("clickable") == "true"):
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                if x2 > x1 and y2 > y1:
                    return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def watch_approval(adb, serial, app_name, device_pattern):
    try:
        dump = subprocess.run(
            [adb, "-s", serial, "exec-out", "uiautomator", "dump", "/dev/tty"],
            capture_output=True, text=True, timeout=15,
        )
        if dump.returncode != 0:
            return False
        position = approval_button(dump.stdout, app_name, device_pattern)
        if position is None:
            return False
        tap = subprocess.run(
            [adb, "-s", serial, "shell", "input", "tap", str(position[0]), str(position[1])],
            capture_output=True, text=True, timeout=5,
        )
        if tap.returncode == 0:
            print("Tapped Allow on matching companion-device approval dialog.", flush=True)
            return True
    except subprocess.TimeoutExpired:
        pass
    return False


def executable(value):
    path = shutil.which(str(value))
    if not path:
        raise RuntimeError(f"Executable not found: {value}")
    return path


def wifi_arguments(emulator, ssid, password):
    """Account for the netsimd Wi-Fi CLI change in newer emulator releases."""
    netsimd = Path(emulator).resolve().parent / "netsimd"
    try:
        result = subprocess.run([str(netsimd), "--help"], capture_output=True, text=True, timeout=10)
        modern = result.returncode == 0 and "--wifi-ssid" in result.stdout
    except (OSError, subprocess.TimeoutExpired):
        modern = False
    if modern:
        result = f"--wifi-ssid {ssid}"
        return result + (f" --wifi-password {password}" if password else "")
    return f"--wifi {ssid}" + (f" {password}" if password else "")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True, help="Android virtual device name")
    parser.add_argument("--model", default="fuji_gfx100ii", help="vcam model")
    parser.add_argument("--vcam", default=str(WORKSPACE / "vcam/build/vcam"), help="vcam executable")
    parser.add_argument("--emulator", default=str(Path(os.environ.get("ANDROID_HOME", "")) / "emulator/emulator"), help="Android emulator executable")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument("--port", type=int, default=5554, help="emulator console port (even; default 5554)")
    parser.add_argument("--ssid", help="custom emulated AP SSID (emulator 36.5+)")
    parser.add_argument("--password", help="WPA2 password for --ssid (minimum 8 characters)")
    parser.add_argument("--apk", type=Path, help="optional APK to install after boot")
    parser.add_argument("--fs", type=Path, help="optional vcam filesystem fixture directory")
    parser.add_argument("--boot-timeout", type=int, default=240, help="seconds to wait for Android boot")
    parser.add_argument("--auto-allow", action="store_true", help="watch for and approve matching companion-device dialogs")
    parser.add_argument("--approval-app", default="FantasyFudge", help="expected app label in approval dialog")
    parser.add_argument("--approval-device", default=r"FUJIFILM-[A-Za-z0-9_-]+", help="regex for camera name in approval dialog")
    args = parser.parse_args()

    if args.port < 5554 or args.port > 5682 or args.port % 2:
        parser.error("--port must be an even number between 5554 and 5682")
    if args.password and (not args.ssid or len(args.password) < 8):
        parser.error("--password requires --ssid and at least 8 characters")
    if args.ssid and (not args.ssid.strip() or any(c.isspace() for c in args.ssid)):
        parser.error("--ssid must be nonempty and contain no whitespace (netsim argument limitation)")
    if args.password and any(c.isspace() for c in args.password):
        parser.error("--password cannot contain whitespace (netsim argument limitation)")
    if args.boot_timeout <= 0:
        parser.error("--boot-timeout must be positive")
    if not args.approval_app.strip():
        parser.error("--approval-app must not be empty")
    try:
        approval_device = re.compile(args.approval_device)
        if approval_device.search(""):
            parser.error("--approval-device must not match an empty string")
    except re.error as error:
        parser.error(f"invalid --approval-device regex: {error}")
    if args.apk and not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    if args.fs and not args.fs.is_dir():
        parser.error(f"fixture directory not found: {args.fs}")

    vcam = executable(args.vcam)
    emulator = executable(args.emulator)
    adb = executable(args.adb)
    serial = f"emulator-{args.port}"
    devices = subprocess.run([adb, "devices"], check=True, capture_output=True, text=True).stdout
    if any(line.split()[:1] == [serial] for line in devices.splitlines()):
        raise RuntimeError(f"{serial} already exists; choose a different --port")

    # Fuji's default address is 192.168.0.1, usually absent on the host.
    # Canon's generic PTP/IP backend currently binds all interfaces regardless.
    camera_command = [vcam, args.model, "tcp", "--ip", "127.0.0.1"]
    if args.fs:
        camera_command += ["--fs", str(args.fs.resolve())]
    emulator_command = [emulator, "-avd", args.avd, "-port", str(args.port)]
    if args.ssid:
        emulator_command += ["-netsim-args", wifi_arguments(emulator, args.ssid, args.password)]

    processes = []
    try:
        print(f"Starting vcam model {args.model}", flush=True)
        processes.append(subprocess.Popen(camera_command, cwd=Path(vcam).resolve().parent))
        time.sleep(1)
        if processes[0].poll() is not None:
            raise RuntimeError(f"vcam exited during startup ({processes[0].returncode}); see its output")
        print(f"Starting {args.avd} as {serial}", flush=True)
        processes.append(subprocess.Popen(emulator_command))
        deadline = time.monotonic() + args.boot_timeout
        while time.monotonic() < deadline:
            for process in processes:
                if process.poll() is not None:
                    raise RuntimeError(f"{process.args[0]} exited ({process.returncode})")
            try:
                boot = subprocess.run(
                    [adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                    capture_output=True, text=True, timeout=5,
                )
                if boot.returncode == 0 and boot.stdout.strip() == "1":
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(2)
        else:
            raise RuntimeError(f"Android did not boot within {args.boot_timeout}s")

        if args.apk:
            subprocess.run([adb, "-s", serial, "install", "-r", str(args.apk.resolve())], check=True)
        print("\nRig ready. Host address from Android: 10.0.2.2 "
              "(Fuji TCP 55740; Canon TCP 15740).", flush=True)
        print("Fudge's Fuji legacy Wi-Fi path hard-codes 192.168.0.1; "
              "this rig does not redirect it.", flush=True)
        print("Discovery broadcasts are not relayed by this rig. Press Ctrl-C to stop.", flush=True)
        if args.auto_allow:
            print(f"Watching for companion-device approval for {args.approval_app} "
                  f"and device matching {args.approval_device!r}.", flush=True)
        while True:
            for process in processes:
                if process.poll() is not None:
                    raise RuntimeError(f"{process.args[0]} exited ({process.returncode})")
            if args.auto_allow and watch_approval(adb, serial, args.approval_app, approval_device):
                time.sleep(3)
            else:
                time.sleep(1)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nRig stopped.")
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f"rig: {error}", file=sys.stderr)
        sys.exit(1)
