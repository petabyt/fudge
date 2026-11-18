#!/usr/bin/env python3
"""Run the Fuji camera matrix using one emulator per Wi-Fi security profile."""

import argparse
import asyncio
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time

from ble_support import approve_ble_dialog, peripheral, wait_for_marker
from rig import ROOT, approve_dialog, camera_info, emulator_main, executable, stop, vcam_command

CASES = {
    "xa2-wifi": ("fuji_x_a2", False),
    "xh1-wifi": ("fuji_x_h1", False),
    "xh1-ble": ("fuji_x_h1", True),
    "xt30-ble": ("fuji_x_t30", True),
    "xt5-ble": ("fuji_x_t5", True),
}
PACKAGE = "dev.danielc.fantasyfudge.nightly"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"


def run_case(name, model, bluetooth, ssid, password, args, adb, rig, root):
    artifacts = root / name
    artifacts.mkdir(parents=True, exist_ok=True)
    camera = ble = instrumentation = None
    camera_log = None
    try:
        subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], check=True, timeout=30)
        subprocess.run(adb + ["shell", "pm", "clear", PACKAGE], check=True, timeout=30)
        for permission in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES"):
            subprocess.run(adb + ["shell", "pm", "grant", PACKAGE, "android.permission." + permission],
                           check=True, timeout=30)
        subprocess.run(adb + ["logcat", "-c"], check=True, timeout=30)
        info = camera_info(args.vcam, model)
        pairing = info.get("bluetooth", {}).get("pairing")
        if bluetooth:
            if pairing not in ("legacy", "secure"):
                raise RuntimeError(f"unsupported pairing mode: {pairing}")
            with socket.socket() as probe:
                probe.settimeout(2)
                if probe.connect_ex(("127.0.0.1", 55740)) == 0:
                    raise RuntimeError("TCP camera already reachable before BLE handover")
            (artifacts / "vcam.log").write_text("")
            cmd = [sys.executable, __file__, "--internal-peripheral", "--vcam-dir", str(args.vcam_dir),
                   "--device", model, "--vcam", args.vcam, "--pairing", pairing,
                   "--ssid", ssid, "--password", password, "--camera-log", str(artifacts / "vcam.log")]
            if os.environ.get("VCAM_FILESYSTEM"):
                cmd.extend(["--filesystem", os.environ["VCAM_FILESYSTEM"]])
            ble = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
            wait_for_marker(ble, "BLE_POC_ADVERTISING", artifacts / "ble.log", 30)
        else:
            command = vcam_command(args.vcam, model, os.environ.get("VCAM_FILESYSTEM"))
            command.extend(["--ssid", ssid])
            if password:
                command.extend(["--password", password])
            camera_log = (artifacts / "vcam.log").open("w")
            camera = subprocess.Popen(command, stdout=camera_log, stderr=subprocess.STDOUT)
        method = "connectFujiBluetooth" if bluetooth else "connectsToFujiCamera"
        with (artifacts / "instrumentation.log").open("w") as output:
            instrumentation = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "class",
                "dev.danielc.fudge.EmulatorCameraTest#" + method, RUNNER], stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + args.timeout
            while instrumentation.poll() is None:
                for label, process in (("emulator rig", rig), ("camera", camera), ("BLE peripheral", ble)):
                    if process and process.poll() is not None:
                        raise RuntimeError(f"{label} exited ({process.returncode})")
                if time.monotonic() >= deadline:
                    raise RuntimeError("instrumentation timed out")
                handover = bluetooth and (artifacts / "ble.log").exists() and (
                    "BLE handover trigger received; starting TCP camera" in
                    (artifacts / "ble.log").read_text(errors="replace"))
                if bluetooth and not handover:
                    approve_ble_dialog(adb, artifacts / "dialog.xml", artifacts / "ble.log" if pairing == "secure" else None)
                else:
                    approve_dialog(adb)
                time.sleep(1)
        result = (artifacts / "instrumentation.log").read_text(errors="replace")
        if instrumentation.returncode or "OK (1 test)" not in result:
            raise RuntimeError("instrumentation failed")
        if bluetooth:
            log = (artifacts / "ble.log").read_text(errors="replace")
            if "BLE handover trigger received; starting TCP camera" not in log:
                raise RuntimeError("BLE handover trigger not received")
            if "BLE handover: TCP camera listening" not in log:
                raise RuntimeError("BLE-triggered TCP camera did not start")
            if pairing == "secure" and "Secure status acknowledged" not in log:
                raise RuntimeError("secure pairing not acknowledged")
        if camera_log:
            camera_log.flush()
        log = (artifacts / "vcam.log").read_text(errors="replace")
        if "Request phase 0x1002" not in log:
            raise RuntimeError("vcam did not receive PTP OpenSession")
        app_log = subprocess.run(adb + ["logcat", "-d", "-v", "threadtime"], capture_output=True,
                                 text=True, check=True, timeout=30).stdout
        if f"Connected to {info['model']}" not in app_log:
            raise RuntimeError(f"Fudge did not report connecting to {info['model']}")
    finally:
        stop(instrumentation)
        try:
            with (artifacts / "logcat.txt").open("w") as output:
                subprocess.run(adb + ["logcat", "-d", "-v", "threadtime"], stdout=output, timeout=30, check=False)
        finally:
            stop(ble)
            stop(camera)
            if camera_log:
                camera_log.close()


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--internal-emulator":
        emulator_main(sys.argv[2:])
        return 0
    if len(sys.argv) > 1 and sys.argv[1] == "--internal-peripheral":
        parser = argparse.ArgumentParser()
        parser.add_argument("--vcam-dir", type=Path, required=True)
        parser.add_argument("--device", required=True)
        parser.add_argument("--vcam", required=True)
        parser.add_argument("--pairing", choices=("legacy", "secure"), required=True)
        parser.add_argument("--ssid", required=True)
        parser.add_argument("--password", required=True)
        parser.add_argument("--camera-log", type=Path, required=True)
        parser.add_argument("--filesystem")
        parser.add_argument("--transport", default="android-netsim")
        asyncio.run(peripheral(parser.parse_args(sys.argv[2:])))
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vcam-dir", type=Path, help="vcam checkout (required for BLE cases)")
    parser.add_argument("--case", choices=CASES, action="append", help="run only selected cases; repeatable")
    parser.add_argument("--timeout", type=int, default=150)
    parser.add_argument("--show-ui", action="store_true", help="show the emulator window")
    parser.add_argument("--no-test", action="store_true", help="run emulator and vcam for manual testing")
    parser.add_argument("--device", default="fuji_x_h1", help="vcam model for --no-test")
    args = parser.parse_args()
    if args.no_test:
        if args.case:
            parser.error("--no-test cannot be combined with --case")
        emulator_main((["--show-ui"] if args.show_ui else []) + ["--device", args.device])
        return 0
    filesystem = os.environ.get("VCAM_FILESYSTEM")
    if not filesystem or not Path(filesystem).is_dir():
        parser.error("set VCAM_FILESYSTEM to a directory containing camera images with thumbnails")
    selected = list(dict.fromkeys(args.case or CASES))
    if any(CASES[case][1] for case in selected):
        if not args.vcam_dir or not (args.vcam_dir.expanduser() / "ble/fuji-ble.py").is_file():
            parser.error("BLE cases require --vcam-dir containing ble/fuji-ble.py")
        args.vcam_dir = args.vcam_dir.expanduser().resolve()
    args.vcam = executable(os.environ.get("VCAM_BINARY", "vcam"))
    adb = [executable("adb"), "-s", "emulator-5554"]
    root = Path(os.environ.get("ARTIFACTS", "test-results/camera-matrix")).resolve()
    root.mkdir(parents=True, exist_ok=True)
    groups = [("open", "fuji_x_h1", [c for c in selected if c != "xt5-ble"]),
              ("secure", "fuji_x_t5", [c for c in selected if c == "xt5-ble"])]
    results = {}
    for group, ap_model, cases in groups:
        if not cases:
            continue
        info = camera_info(args.vcam, ap_model)
        ssid, password = info["wifi"]["ssid"], info["wifi"]["password"]
        if (group == "open" and password) or (group == "secure" and not password):
            raise RuntimeError(f"unexpected {group} AP security for {ap_model}")
        env = os.environ.copy()
        env.update(ARTIFACTS=str(root / (group + "-rig")), VCAM_BINARY=args.vcam)
        cmd = [sys.executable, __file__, "--internal-emulator", "--external-vcam", "--device", ap_model]
        if args.show_ui or any(CASES[case][1] for case in cases):
            cmd.append("--show-ui")
        rig = subprocess.Popen(cmd, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        try:
            wait_for_marker(rig, "Emulator ready.", root / (group + "-rig.log"), 420)
            test_apk = os.environ.get("FUDGE_TEST_APK", str(ROOT / "app/build/outputs/apk/androidTest/nightly/release/app-nightly-release-androidTest.apk"))
            subprocess.run(adb + ["install", "-r", test_apk], check=True, timeout=120)
            for case in cases:
                print(f"Running {case}", flush=True)
                try:
                    run_case(case, *CASES[case], ssid, password, args, adb, rig, root)
                    results[case] = "PASS"
                except (RuntimeError, OSError, subprocess.SubprocessError) as error:
                    results[case] = f"FAIL: {error}"
                print(f"{case}: {results[case]}", flush=True)
        finally:
            if rig.poll() is None:
                rig.send_signal(signal.SIGINT)
                try:
                    rig.wait(timeout=40)
                except subprocess.TimeoutExpired:
                    stop(rig)
        # adb can briefly retain the serial after the emulator process exits.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            devices = subprocess.run([adb[0], "devices"], capture_output=True, text=True, timeout=10)
            if not any(line.split()[:1] == ["emulator-5554"] for line in devices.stdout.splitlines()):
                break
            time.sleep(1)
    print("\nCamera matrix:")
    for case in selected:
        print(f"  {case}: {results.get(case, 'NOT RUN')}")
    return 0 if all(results.get(case) == "PASS" for case in selected) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Camera matrix failed: {error}", file=sys.stderr)
        sys.exit(1)
