import re
import unittest

from rig import approval_button


class ApprovalDialogTest(unittest.TestCase):
    def setUp(self):
        self.pattern = re.compile(r"FUJIFILM-[A-Za-z0-9_-]+")
        self.xml = '''<?xml version="1.0"?><hierarchy>
          <node package="com.android.companiondevicemanager" text="Allow the app FantasyFudge to access FUJIFILM-XA2-1234?"/>
          <node package="com.android.companiondevicemanager" text="Allow" enabled="true" clickable="true" bounds="[100,200][300,260]"/>
          <node package="com.android.companiondevicemanager" text="Don't allow" enabled="true" clickable="true" bounds="[100,270][300,330]"/>
        </hierarchy>UI hierchary dumped to: /dev/tty'''

    def test_matching_dialog(self):
        self.assertEqual(approval_button(self.xml, "FantasyFudge", self.pattern), (200, 230))

    def test_wrong_app_or_device(self):
        self.assertIsNone(approval_button(self.xml, "OtherApp", self.pattern))
        self.assertIsNone(approval_button(self.xml.replace("FUJIFILM-XA2-1234", "Other"), "FantasyFudge", self.pattern))

    def test_other_package_and_disabled_button(self):
        self.assertIsNone(approval_button(self.xml.replace("companiondevicemanager", "permissioncontroller"), "FantasyFudge", self.pattern))
        self.assertIsNone(approval_button(self.xml.replace('text="Allow" enabled="true"', 'text="Allow" enabled="false"'), "FantasyFudge", self.pattern))

    def test_unusable_dump(self):
        self.assertIsNone(approval_button("UI dump failed", "FantasyFudge", self.pattern))
        self.assertIsNone(approval_button(self.xml.replace("</hierarchy>", ""), "FantasyFudge", self.pattern))


if __name__ == "__main__":
    unittest.main()
