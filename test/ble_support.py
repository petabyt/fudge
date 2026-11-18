#!/usr/bin/env python3
"""BLE peripheral and pairing helpers for the emulator test rig."""

import asyncio
import importlib.util
import re
import secrets
import signal
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

from rig import camera_wifi, stop, vcam_command


ADDRESS = "F6:F7:F8:F9:FA:FB"
CAMERA_NAME = "vcam-XT30"


def center(node):
    if node.get("enabled") != "true" or node.get("clickable") != "true":
        return None
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not bounds:
        return None
    x1, y1, x2, y2 = map(int, bounds.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2) if x2 > x1 and y2 > y1 else None


def companion_tap(xml):
    """Find only the virtual camera or its approval in a companion dialog."""
    root = ET.fromstring(xml)
    nodes = [node for node in root.iter("node")
             if node.get("package", "").endswith(".companiondevicemanager")]
    if not any(CAMERA_NAME in (node.get("text", ""), node.get("content-desc", "")) for node in nodes):
        return None

    for node in nodes:
        if node.get("text") == "Allow" and center(node):
            return center(node)
    parents = {child: parent for parent in nodes for child in parent}
    for node in nodes:
        if CAMERA_NAME not in (node.get("text", ""), node.get("content-desc", "")):
            continue
        while node is not None and node in nodes:
            if center(node):
                return center(node)
            node = parents.get(node)
    return None


def pairing_tap(xml, comparison):
    nodes = [node for node in ET.fromstring(xml).iter("node") if node.get("package") == "com.android.settings"]
    if not comparison or not any(node.get("text") == f"Pair with {CAMERA_NAME}?" for node in nodes):
        return None
    if not any(node.get("resource-id") == "com.android.settings:id/pairing_subhead"
               and node.get("text") == comparison for node in nodes):
        return None
    for node in nodes:
        if node.get("resource-id") == "android:id/button1" and node.get("text") == "Pair":
            return center(node)
    return None


def approve_ble_dialog(adb, snapshot=None, ble_log=None):
    try:
        result = subprocess.run(adb + ["exec-out", "uiautomator", "dump", "/dev/tty"],
                                capture_output=True, text=True, timeout=15)
        start, end = result.stdout.find("<?xml"), result.stdout.rfind("</hierarchy>")
        if result.returncode or start < 0 or end < 0:
            return
        if snapshot:
            snapshot.write_text(result.stdout[start:end + len("</hierarchy>")])
        xml = result.stdout[start:end + len("</hierarchy>")]
        position = companion_tap(xml)
        if not position and ble_log and ble_log.exists():
            comparisons = re.findall(r"Virtual camera pairing comparison: (\d{6})", ble_log.read_text())
            position = pairing_tap(xml, comparisons[-1] if comparisons else None)
        if position:
            subprocess.run(adb + ["shell", "input", "tap", *map(str, position)], check=True, timeout=5)
            print("Selected or approved the vcam companion device.", flush=True)
    except (subprocess.SubprocessError, OSError, ET.ParseError):
        pass


async def peripheral(args):
    # Import the replica from the user's vcam checkout, without changing it.
    from bumble.device import Device
    from bumble.hci import Address
    from bumble.host import Host
    from bumble.transport import open_transport

    spec = importlib.util.spec_from_file_location("fuji_ble", args.vcam_dir / "ble/fuji-ble.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)

    class TriggeredCamera:
        ready = False
        process = None
        log = None

        async def start(self):
            if self.process:
                if self.process.poll() is not None:
                    raise RuntimeError("triggered camera exited")
                return
            print("BLE handover trigger received; starting TCP camera", flush=True)
            command = vcam_command(args.vcam, args.device, args.filesystem)
            command.extend(["--ssid", args.ssid])
            if args.password:
                command.extend(["--password", args.password])
            self.log = args.camera_log.open("w")
            self.process = subprocess.Popen(command, stdout=self.log, stderr=subprocess.STDOUT)
            deadline = asyncio.get_running_loop().time() + 10
            while asyncio.get_running_loop().time() < deadline:
                if self.process.poll() is not None:
                    raise RuntimeError(f"triggered camera exited ({self.process.returncode})")
                if "Socket listening" in args.camera_log.read_text(errors="replace"):
                    self.ready = True
                    print("BLE handover: TCP camera listening", flush=True)
                    return
                await asyncio.sleep(0.1)
            raise RuntimeError("triggered camera did not start listening")

        def close(self):
            stop(self.process)
            if self.log:
                self.log.close()

    class TestPairing(module.ConfirmPairing):
        async def compare_numbers(self, number, digits):
            print(f"Virtual camera pairing comparison: {number:0{digits}d}", flush=True)
            return True

    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stopped.set)
    ssid, password = camera_wifi(args.vcam, args.device)
    if args.ssid is not None:
        ssid = args.ssid
    if args.password is not None:
        password = args.password
    camera = TriggeredCamera()
    try:
        async with await open_transport(args.transport) as hci:
            device = Device(module.NAME, address=Address(ADDRESS), host=Host(hci.source, hci.sink))
            module.configure(device, secrets.token_bytes(4), secure=args.pairing == "secure",
                             delegate_factory=TestPairing, wifi=camera,
                             identity={"ssid": ssid, "password": password})
            await device.power_on()
            await device.start_advertising(auto_restart=True)
            print("BLE_POC_ADVERTISING", flush=True)
            task = asyncio.create_task(stopped.wait())
            try:
                await asyncio.wait((hci.source.terminated, task), return_when=asyncio.FIRST_COMPLETED)
                if not stopped.is_set():
                    raise RuntimeError("netsim Bluetooth transport disconnected")
            finally:
                task.cancel()
                await device.stop_advertising()
    finally:
        camera.close()


def wait_for_marker(process, marker, log_path, timeout):
    ready = threading.Event()

    def copy():
        with log_path.open("w") as log:
            for line in process.stdout:
                print(line, end="", flush=True)
                log.write(line)
                log.flush()
                if marker in line:
                    ready.set()

    thread = threading.Thread(target=copy, daemon=True)
    thread.start()
    deadline = time.monotonic() + timeout
    while not ready.wait(0.5):
        if process.poll() is not None:
            raise RuntimeError(f"process exited ({process.returncode}); see {log_path}")
        if time.monotonic() >= deadline:
            raise RuntimeError(f"timed out waiting for {marker}; see {log_path}")
    return thread
