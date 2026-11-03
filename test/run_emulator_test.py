#!/usr/bin/env python3
"""Start the emulator and vcam, run the camera test, and collect diagnostics."""

import os
from pathlib import Path
import subprocess
import sys
import time
import tempfile

from rig import executable, wifi_arguments


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    vcam = executable(os.environ["VCAM_BINARY"])
    apk = os.environ["FUDGE_APK"]
    test_apk = os.environ["FUDGE_TEST_APK"]
    avd = os.environ.get("AVD_NAME", "fudge-ci")
    artifacts = Path(os.environ.get("ARTIFACTS", "test-results/emulator"))
    artifacts.mkdir(parents=True, exist_ok=True)
    adb = executable("adb")
    avdmanager = executable("avdmanager")
    emulator = executable(sdk / "emulator/emulator")
    serial = "emulator-5554"
    base = [adb, "-s", serial]
    emulator_process = vcam_process = None
    failed = True

    if not os.environ.get("ANDROID_AVD_HOME"):
        os.environ["ANDROID_AVD_HOME"] = tempfile.mkdtemp(prefix="fudge-avd-")
    avd_home = Path(os.environ["ANDROID_AVD_HOME"])
    avd_home.mkdir(parents=True, exist_ok=True)
    print(f"AVD directory: {avd_home}", flush=True)

    try:
        subprocess.run([adb, "start-server"], check=True, timeout=30)
        devices = subprocess.run([adb, "devices"], check=True, capture_output=True, text=True, timeout=30)
        if any(line.split()[:1] == [serial] for line in devices.stdout.splitlines()):
            raise RuntimeError(f"{serial} already exists; refusing to use an existing emulator")
        subprocess.run([avdmanager, "create", "avd", "-n", avd, "-k",
                        "system-images;android-35;google_apis;x86_64"],
                       input="no\n", text=True, check=True, timeout=120)

        avd_ini = avd_home / f"{avd}.ini"
        if not avd_ini.is_file():
            raise RuntimeError(
                f"avdmanager did not create {avd_ini}; "
                "check its output and Android SDK configuration"
            )

        with (artifacts / "emulator.log").open("w") as log:
            emulator_process = subprocess.Popen([
                emulator, "-avd", avd, "-port", "5554", "-no-window", "-no-audio",
                "-no-snapshot", "-no-boot-anim", "-no-metrics", "-gpu", "off", "-cores", "2",
                "-netsim-args", wifi_arguments(emulator, "FUJIFILM-XA2-1234", None),
            ], stdout=log, stderr=subprocess.STDOUT)
        with (artifacts / "vcam.log").open("w") as log:
            vcam_process = subprocess.Popen(["stdbuf", "-oL", "-eL", vcam, "fuji_x_t4", "tcp",
                                              "--ip", "127.0.0.1"], stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            for name, process in (("emulator", emulator_process), ("vcam", vcam_process)):
                if process.poll() is not None:
                    raise RuntimeError(f"{name} exited during boot ({process.returncode})")
            try:
                boot = subprocess.run(base + ["shell", "getprop", "sys.boot_completed"],
                                      capture_output=True, text=True, timeout=5)
                if boot.returncode == 0 and boot.stdout.strip() == "1":
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(2)
        else:
            raise RuntimeError("emulator boot timed out")
        subprocess.run(base + ["install", "-r", apk], check=True, timeout=120)
        subprocess.run(base + ["install", "-r", test_apk], check=True, timeout=120)
        subprocess.run([sys.executable, str(Path(__file__).with_name("ci_test.py")),
                        "--serial", serial, "--timeout", "150"], check=True, timeout=180)
        if vcam_process.poll() is not None or emulator_process.poll() is not None:
            raise RuntimeError("emulator or vcam exited during the test")
        failed = False
    finally:
        if failed and emulator_process is not None:
            for filename, command, timeout in (
                ("logcat.txt", ["logcat", "-d", "-v", "threadtime"], 15),
                ("screenshot.png", ["exec-out", "screencap", "-p"], 15),
                ("ui.xml", ["exec-out", "uiautomator", "dump", "/dev/tty"], 20),
            ):
                try:
                    with (artifacts / filename).open("wb") as output:
                        subprocess.run(base + command, stdout=output, stderr=subprocess.DEVNULL, timeout=timeout)
                except (OSError, subprocess.TimeoutExpired) as error:
                    print(f"Could not collect {filename}: {error}", file=sys.stderr)
        if emulator_process and emulator_process.poll() is None:
            try:
                subprocess.run(base + ["emu", "kill"], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                pass
        stop(emulator_process)
        stop(vcam_process)


if __name__ == "__main__":
    try:
        main()
    except (KeyError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Emulator test failed: {error}", file=sys.stderr)
        sys.exit(1)
