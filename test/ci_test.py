#!/usr/bin/env python3
"""Exercise Fudge's Fuji Wi-Fi connection on a booted, disposable emulator."""

import argparse
import re
import subprocess
import sys
import time

from rig import executable, watch_approval


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--package", default="dev.danielc.fantasyfudge.nightly")
    args = parser.parse_args()
    if args.timeout <= 0 or not re.fullmatch(r"emulator-\d+", args.serial) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("use a positive timeout and an emulator-N serial")
    adb = executable(args.adb)
    base = [adb, "-s", args.serial]

    def run(*command):
        return subprocess.run(base + list(command), check=True, timeout=30)

    # This modifies only the emulator's networking, and is removed in finally.
    rule = ["OUTPUT", "-d", "192.168.0.1", "-p", "tcp", "-m", "multiport",
            "--dports", "55740,55741,55742", "-j", "DNAT", "--to-destination", "10.0.2.2"]
    instrumentation = None
    installed_rule = False
    try:
        run("root")
        run("wait-for-device")
        run("shell", "iptables", "-t", "nat", "-I", *rule)
        installed_rule = True
        # This is a disposable emulator; avoid stale messages from earlier runs.
        run("logcat", "-c")
        instrumentation = subprocess.Popen(base + ["shell", "am", "instrument", "-w", "-e", "class",
            "dev.danielc.fudge.EmulatorCameraTest", f"{args.package}.test/androidx.test.runner.AndroidJUnitRunner"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + args.timeout
        pattern = re.compile(r"FUJIFILM-[A-Za-z0-9_-]+")
        while time.monotonic() < deadline:
            if instrumentation.poll() is not None:
                output = instrumentation.communicate()[0]
                print(output, end="", flush=True)
                if instrumentation.returncode != 0 or "OK (1 test)" not in output:
                    raise RuntimeError("Fuji instrumentation test failed")
                print("Fuji camera session established.")
                return
            watch_approval(adb, args.serial, "FantasyFudge", pattern)
            time.sleep(1)
        raise RuntimeError(f"camera session not established within {args.timeout}s")
    finally:
        if instrumentation and instrumentation.poll() is None:
            instrumentation.terminate()
            instrumentation.wait(timeout=5)
        if installed_rule:
            try:
                run("shell", "iptables", "-t", "nat", "-D", *rule)
            except (subprocess.SubprocessError, OSError) as error:
                print(f"warning: could not remove emulator NAT rule: {error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"CI test failed: {error}", file=sys.stderr)
        sys.exit(1)
