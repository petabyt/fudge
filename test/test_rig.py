import subprocess
import tempfile
import unittest
from unittest.mock import patch

from ble_support import companion_tap, pairing_tap
from rig import camera_wifi, netsim_wifi_args, vcam_command


class CameraConfigTest(unittest.TestCase):
    def test_vcam_filesystem(self):
        command = ["stdbuf", "-oL", "-eL", "vcam", "fuji_x_t5", "tcp", "--ip", "127.0.0.1"]
        self.assertEqual(vcam_command("vcam", "fuji_x_t5"), command)
        with tempfile.TemporaryDirectory() as filesystem:
            self.assertEqual(vcam_command("vcam", "fuji_x_t5", filesystem), command + ["--fs", filesystem])
        with self.assertRaisesRegex(RuntimeError, "filesystem directory not found"):
            vcam_command("vcam", "fuji_x_t5", filesystem)

    @patch("rig.subprocess.run")
    def test_camera_wifi_uses_selected_device(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, '{"wifi":{"ssid":"FUJIFILM-XT5-ABCD","password":""}}', '')
        self.assertEqual(camera_wifi("vcam", "fuji_x_t5"), ("FUJIFILM-XT5-ABCD", ""))
        self.assertEqual(run.call_args.args[0], ["vcam", "fuji_x_t5", "tcp", "--dump-camera-json"])

    @patch("rig.subprocess.run")
    def test_invalid_camera_info(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, '{"wifi":{}}', '')
        with self.assertRaisesRegex(RuntimeError, "Invalid Wi-Fi info"):
            camera_wifi("vcam", "bad")

    @patch("rig.subprocess.run")
    def test_failed_camera_query(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, '', 'unknown device')
        with self.assertRaisesRegex(RuntimeError, "unknown device"):
            camera_wifi("vcam", "bad")

    def test_netsim_arguments(self):
        self.assertEqual(netsim_wifi_args("--wifi", "SSID", ""), "--wifi SSID")
        self.assertEqual(netsim_wifi_args("--wifi", "SSID", "password"), "--wifi SSID password")
        self.assertEqual(netsim_wifi_args("--wifi-ssid --wifi-password", "SSID", "password"),
                         "--wifi-ssid SSID --wifi-password password")
        with self.assertRaisesRegex(RuntimeError, "--wifi-password"):
            netsim_wifi_args("--wifi-ssid", "SSID", "password")


class CompanionDialogTest(unittest.TestCase):
    def test_secure_pairing_requires_matching_code_and_camera(self):
        xml = '''<hierarchy><node package="com.android.settings" text="Pair with vcam-XT30?"/>
          <node package="com.android.settings" resource-id="com.android.settings:id/pairing_subhead" text="012345"/>
          <node package="com.android.settings" resource-id="android:id/button1" text="Pair"
          enabled="true" clickable="true" bounds="[10,20][110,60]"/></hierarchy>'''
        self.assertEqual(pairing_tap(xml, "012345"), (60, 40))
        self.assertIsNone(pairing_tap(xml, "654321"))
        self.assertIsNone(pairing_tap(xml.replace("vcam-XT30", "real-camera"), "012345"))

    def test_selects_camera_row(self):
        xml = '''<hierarchy><node package="com.android.companiondevicemanager"
          enabled="true" clickable="true" bounds="[0,20][200,80]">
          <node package="com.android.companiondevicemanager" text="vcam-XT30"/>
          </node></hierarchy>'''
        self.assertEqual(companion_tap(xml), (100, 50))

    def test_approves_matching_camera(self):
        xml = '''<hierarchy><node package="com.android.companiondevicemanager" text="vcam-XT30"/>
          <node package="com.android.companiondevicemanager" text="Allow"
          enabled="true" clickable="true" bounds="[10,20][110,60]"/></hierarchy>'''
        self.assertEqual(companion_tap(xml), (60, 40))

    def test_ignores_other_devices_and_apps(self):
        for package, name in (("com.android.companiondevicemanager", "real-camera"),
                              ("com.example.app", "vcam-XT30")):
            xml = f'''<hierarchy><node package="{package}" text="{name}"
              enabled="true" clickable="true" bounds="[0,0][100,100]"/></hierarchy>'''
            self.assertIsNone(companion_tap(xml))
