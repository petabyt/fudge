#!/usr/bin/env python3
"""Run the Fuji camera test on a disposable Android emulator."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent
SERIAL = "emulator-5554"


def camera_wifi(vcam, device):
    result = subprocess.run([vcam, device, "tcp", "--dump-camera-json"],
                            capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"Could not get camera info for {device}: {result.stderr.strip() or 'vcam failed'}")
    try:
        wifi = json.loads(result.stdout)["wifi"]
        ssid, password = wifi["ssid"], wifi["password"]
        if not isinstance(ssid, str) or not ssid or not isinstance(password, str):
            raise ValueError("expected a nonempty SSID and a string password")
        if any(c.isspace() for c in ssid + password):
            raise ValueError("Wi-Fi SSID and password cannot contain whitespace in netsim arguments")
        if password and len(password) < 8:
            raise ValueError("Wi-Fi password must contain at least 8 characters")
    except (ValueError, KeyError, TypeError) as error:
        raise RuntimeError(f"Invalid Wi-Fi info for {device}: {error}") from error
    return ssid, password


def netsim_wifi_args(help_text, ssid, password):
    if "--wifi-ssid" in help_text:
        args = ["--wifi-ssid", ssid]
        if password:
            if "--wifi-password" not in help_text:
                raise RuntimeError("This netsimd does not advertise --wifi-password support")
            args.extend(["--wifi-password", password])
    else:
        args = ["--wifi", ssid]
        if password:
            args.append(password)
    return " ".join(args)


def executable(name):
    path = shutil.which(str(name))
    if not path:
        raise RuntimeError(f"Executable not found: {name}")
    return path


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def show_output(process, path):
    """Copy emulator output to the terminal and an artifact without blocking it."""
    def copy():
        with path.open("w") as log:
            for line in process.stdout:
                print(line, end="", flush=True)
                log.write(line)
                log.flush()

    thread = threading.Thread(target=copy, daemon=True)
    thread.start()
    return thread


def approve_dialog(adb):
    try:
        result = subprocess.run(adb + ["exec-out", "uiautomator", "dump", "/dev/tty"],
                                capture_output=True, text=True, timeout=15)
        start = result.stdout.find("<?xml")
        end = result.stdout.rfind("</hierarchy>")
        if result.returncode or start < 0 or end < 0:
            return
        root = ET.fromstring(result.stdout[start:end + len("</hierarchy>")])
        nodes = [node for node in root.iter("node")
                 if node.get("package", "").endswith(".companiondevicemanager")]
        text = " ".join(node.get("text", "") for node in nodes)
        if "FantasyFudge" not in text or not re.search(r"FUJIFILM-[A-Za-z0-9_-]+", text):
            return
        for node in nodes:
            if node.get("text") != "Allow" or node.get("enabled") != "true" or node.get("clickable") != "true":
                continue
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                if x2 > x1 and y2 > y1:
                    tap = subprocess.run(adb + ["shell", "input", "tap", str((x1 + x2) // 2),
                                                str((y1 + y2) // 2)], timeout=5)
                    if tap.returncode == 0:
                        print("Approved matching companion-device dialog.", flush=True)
                    return
    except (subprocess.TimeoutExpired, ET.ParseError):
        pass


def stream_app_logcat(adb, finished):
    """Follow the app's current process, including after a restart."""
    logcat = None
    current_pid = None
    try:
        while not finished.is_set():
            try:
                result = subprocess.run(adb + ["shell", "pidof", "dev.danielc.fantasyfudge.nightly"],
                                        capture_output=True, text=True, timeout=5)
                pids = result.stdout.split() if result.returncode == 0 else []
                pid = pids[0] if pids and pids[0].isdigit() else None
                if pid != current_pid or (pid and (logcat is None or logcat.poll() is not None)):
                    stop(logcat)
                    logcat = None
                    current_pid = pid
                    if pid and not finished.is_set():
                        print(f"Streaming app logcat (PID {pid}).", flush=True)
                        logcat = subprocess.Popen(adb + ["logcat", "-v", "threadtime", "--pid", pid])
            except (OSError, subprocess.SubprocessError) as error:
                print(f"Could not stream app logcat: {error}", file=sys.stderr)
            finished.wait(2)
    finally:
        stop(logcat)


def run_fuji_test(adb, no_test=False, processes=()):
    def run(*args):
        return subprocess.run(adb + list(args), check=True, timeout=30)

    rule = ["OUTPUT", "-d", "192.168.0.1", "-p", "tcp", "-m", "multiport",
            "--dports", "55740,55741,55742", "-j", "DNAT", "--to-destination", "10.0.2.2"]
    instrumentation = None
    installed = False
    logcat_finished = threading.Event()
    logcat_thread = None
    try:
        run("root")
        run("wait-for-device")
        run("shell", "iptables", "-t", "nat", "-I", *rule)
        installed = True
        run("logcat", "-c")
        logcat_thread = threading.Thread(target=stream_app_logcat, args=(adb, logcat_finished), daemon=True)
        logcat_thread.start()
        if no_test:
            print("Emulator ready. Open FantasyFudge to connect; press Ctrl+C to stop.", flush=True)
            try:
                while True:
                    for name, process in processes:
                        if process.poll() is not None:
                            raise RuntimeError(f"{name} exited ({process.returncode})")
                    time.sleep(1)
            except KeyboardInterrupt:
                print("Stopping emulator rig.", flush=True)
                return
        instrumentation = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "class",
            "dev.danielc.fudge.EmulatorCameraTest",
            "dev.danielc.fantasyfudge.nightly.test/androidx.test.runner.AndroidJUnitRunner"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            if instrumentation.poll() is not None:
                output = instrumentation.communicate()[0]
                print(output, end="", flush=True)
                if instrumentation.returncode or "OK (1 test)" not in output:
                    raise RuntimeError("Fuji instrumentation test failed")
                print("Fuji camera session established.")
                return
            approve_dialog(adb)
            time.sleep(1)
        raise RuntimeError("camera session not established within 150 seconds")
    finally:
        logcat_finished.set()
        if logcat_thread:
            logcat_thread.join()
        stop(instrumentation)
        if installed:
            try:
                run("shell", "iptables", "-t", "nat", "-D", *rule)
            except (subprocess.SubprocessError, OSError) as error:
                print(f"warning: could not remove emulator NAT rule: {error}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--show-ui", action="store_true", help="show the emulator window")
    parser.add_argument("--no-test", action="store_true", help="keep the rig running for manual testing")
    parser.add_argument("--device", default="fuji_x_t5", help="vcam camera model (default: fuji_x_t5)")
    args = parser.parse_args()
    sdk = Path(os.environ.setdefault("ANDROID_HOME", str(Path.home() / "Android/Sdk"))).expanduser()
    vcam = os.environ.get("VCAM_BINARY") or shutil.which("vcam") or "/usr/local/vcam"
    vcam = executable(vcam)
    ssid, password = camera_wifi(vcam, args.device)
    apk = os.environ.get("FUDGE_APK", str(ROOT / "app/build/outputs/apk/nightly/release/app-nightly-release.apk"))
    test_apk = os.environ.get("FUDGE_TEST_APK", str(ROOT / "app/build/outputs/apk/androidTest/nightly/release/app-nightly-release-androidTest.apk"))
    avd = os.environ.get("AVD_NAME", "fudge-ci")
    artifacts = Path(os.environ.get("ARTIFACTS", "test-results/emulator"))
    artifacts.mkdir(parents=True, exist_ok=True)
    adb_exe = executable("adb")
    adb = [adb_exe, "-s", SERIAL]
    avdmanager = executable(sdk / "cmdline-tools/latest/bin/avdmanager")
    emulator = executable(sdk / "emulator/emulator")
    emulator_process = vcam_process = output_thread = None
    failed = True
    original_avd_home = os.environ.get("ANDROID_AVD_HOME")
    temporary_avd = None

    try:
        if not original_avd_home:
            temporary_avd = tempfile.TemporaryDirectory(prefix="fudge-avd-")
            os.environ["ANDROID_AVD_HOME"] = temporary_avd.name
        avd_home = Path(os.environ["ANDROID_AVD_HOME"])
        avd_home.mkdir(parents=True, exist_ok=True)
        print(f"AVD directory: {avd_home}", flush=True)
        subprocess.run([adb_exe, "start-server"], check=True, timeout=30)
        devices = subprocess.run([adb_exe, "devices"], check=True, capture_output=True, text=True, timeout=30)
        if any(line.split()[:1] == [SERIAL] for line in devices.stdout.splitlines()):
            raise RuntimeError(f"{SERIAL} already exists; refusing to use an existing emulator")
        print("Creating AVD (answering no to custom hardware profile).", flush=True)
        subprocess.run([avdmanager, "create", "avd", "-n", avd, "-k",
                        "system-images;android-35;google_apis;x86_64"],
                       input="no\n", text=True, check=True, timeout=120)
        print(flush=True)
        if not (avd_home / f"{avd}.ini").is_file():
            raise RuntimeError(f"avdmanager did not create {avd_home / (avd + '.ini')}")

        netsimd = Path(emulator).resolve().parent / "netsimd"
        try:
            help_text = subprocess.run([str(netsimd), "--help"], capture_output=True, text=True, timeout=10)
            netsim_help = help_text.stdout if help_text.returncode == 0 else ""
        except (OSError, subprocess.TimeoutExpired):
            netsim_help = ""
        wifi = netsim_wifi_args(netsim_help, ssid, password)
        print(f"Starting emulator; output also saved to {artifacts / 'emulator.log'}", flush=True)
        emulator_command = [emulator, "-avd", avd, "-port", "5554", "-no-audio",
            "-no-snapshot", "-no-boot-anim", "-no-metrics", "-gpu", "off", "-cores", "2",
            "-netsim-args", wifi]
        if not args.show_ui:
            emulator_command.append("-no-window")
        emulator_process = subprocess.Popen(emulator_command, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, text=True, errors="replace")
        output_thread = show_output(emulator_process, artifacts / "emulator.log")
        with (artifacts / "vcam.log").open("w") as log:
            vcam_process = subprocess.Popen(["stdbuf", "-oL", "-eL", vcam, args.device, "tcp",
                                              "--ip", "127.0.0.1"], stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            for name, process in (("emulator", emulator_process), ("vcam", vcam_process)):
                if process.poll() is not None:
                    raise RuntimeError(f"{name} exited during boot ({process.returncode}); see {artifacts / (name + '.log')}")
            try:
                boot = subprocess.run(adb + ["shell", "getprop", "sys.boot_completed"],
                                      capture_output=True, text=True, timeout=5)
                if boot.returncode == 0 and boot.stdout.strip() == "1":
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(2)
        else:
            raise RuntimeError("emulator boot timed out")
        # Keep the emulator's cellular data available when camera Wi-Fi is connected.
        subprocess.run(adb + ["shell", "svc", "data", "enable"], check=True, timeout=30)
        subprocess.run(adb + ["shell", "settings", "put", "global", "mobile_data_always_on", "1"],
                       check=True, timeout=30)
        print("Emulator booted; installing APKs.", flush=True)
        subprocess.run(adb + ["install", "-r", apk], check=True, timeout=120)
        if not args.no_test:
            subprocess.run(adb + ["install", "-r", test_apk], check=True, timeout=120)
        for permission in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES"):
            subprocess.run(adb + ["shell", "pm", "grant", "dev.danielc.fantasyfudge.nightly",
                                  f"android.permission.{permission}"], check=True, timeout=30)
        run_fuji_test(adb, no_test=args.no_test,
                      processes=(("emulator", emulator_process), ("vcam", vcam_process)))
        if not args.no_test and (vcam_process.poll() is not None or emulator_process.poll() is not None):
            raise RuntimeError("emulator or vcam exited during the test")
        failed = False
    finally:
        if failed and emulator_process and emulator_process.poll() is None:
            for filename, command, timeout in (
                ("logcat.txt", ["logcat", "-d", "-v", "threadtime"], 15),
                ("screenshot.png", ["exec-out", "screencap", "-p"], 15),
                ("ui.xml", ["exec-out", "uiautomator", "dump", "/dev/tty"], 20),
            ):
                try:
                    with (artifacts / filename).open("wb") as output:
                        result = subprocess.run(adb + command, stdout=output, stderr=subprocess.PIPE, timeout=timeout)
                    if result.returncode:
                        print(f"Could not collect {filename}: {result.stderr.decode(errors='replace').strip()}", file=sys.stderr)
                except (OSError, subprocess.TimeoutExpired) as error:
                    print(f"Could not collect {filename}: {error}", file=sys.stderr)
        elif failed and emulator_process:
            print("Emulator exited; skipping device diagnostics.", file=sys.stderr)
        if emulator_process and emulator_process.poll() is None:
            try:
                subprocess.run(adb + ["emu", "kill"], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                pass
        stop(emulator_process)
        stop(vcam_process)
        if output_thread:
            output_thread.join(timeout=5)
        if temporary_avd:
            try:
                temporary_avd.cleanup()
            finally:
                if original_avd_home is None:
                    os.environ.pop("ANDROID_AVD_HOME", None)
                else:
                    os.environ["ANDROID_AVD_HOME"] = original_avd_home


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Emulator test failed: {error}", file=sys.stderr)
        sys.exit(1)
