# test rig

A headless test rig that completely automates Android WiFi pairing and module testing.

Currently tests libfuji with [vcam](https://github.com/petabyt/vcam)

Run from the project root with `python3 test/emulator_test.py`. By default it
uses `~/Android/Sdk`, `vcam` on PATH (or `/usr/local/vcam`), and the nightly
release APKs under `app/build/outputs/apk`. Set `ANDROID_HOME`, `VCAM_BINARY`,
`FUDGE_APK`, or `FUDGE_TEST_APK` to override these defaults. `adb` must be on PATH.
Pass `--show-ui` to display the emulator window instead of running headless.
The rig grants Bluetooth scan/connect and nearby Wi-Fi runtime permissions to the
nightly app with ADB after installation, before starting instrumentation. The
camera test therefore expects those permissions to be granted when run separately.

The temporary AVD uses Python's temporary directory and is removed after the
emulator stops; set `TMPDIR` to a directory with enough free space if `/tmp` is
too small. A user-supplied `ANDROID_AVD_HOME` is not removed. Emulator output is printed live
and saved alongside other diagnostics in `test-results/emulator` (override with
`ARTIFACTS`). `ANDROID_AVD_HOME` and `AVD_NAME` can also be overridden.
During the Fuji test, logcat messages from the app process are printed live.
