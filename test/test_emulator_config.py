import subprocess
import unittest
from unittest.mock import patch

from emulator_test import camera_wifi, netsim_wifi_args


class CameraConfigTest(unittest.TestCase):
    @patch("emulator_test.subprocess.run")
    def test_camera_wifi_uses_selected_device(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, '{"wifi":{"ssid":"FUJIFILM-XT5-ABCD","password":""}}', '')
        self.assertEqual(camera_wifi("vcam", "fuji_x_t5"), ("FUJIFILM-XT5-ABCD", ""))
        self.assertEqual(run.call_args.args[0], ["vcam", "fuji_x_t5", "tcp", "--dump-camera-json"])

    @patch("emulator_test.subprocess.run")
    def test_invalid_camera_info(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, '{"wifi":{}}', '')
        with self.assertRaisesRegex(RuntimeError, "Invalid Wi-Fi info"):
            camera_wifi("vcam", "bad")

    @patch("emulator_test.subprocess.run")
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
