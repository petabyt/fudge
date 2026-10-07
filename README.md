# FantasyFudge (temp name)

This is an Android app that can connect to mirrorless cameras and various other devices, serving as an alternative to the vendor-provided app.

| <img src='https://s1.danielc.dev/screenshots/Screenshot_20260128-140111.png' width='350'> | <img src='https://s1.danielc.dev/screenshots/Screenshot_20260430-204144.png' width='350'> | <img src='https://s1.danielc.dev/screenshots/Screenshot_20260430-205138.png' width='350'> | <img src='https://s1.danielc.dev/screenshots/Screenshot_20260804-005001.png' width='350'> |
|-|-|-|-|

Install Beta builds:  
<a href="https://github.com/petabyt/fudge/releases/tag/latest"><img src="https://fudge.fm/images/badge_obtainium.png" width=200></a> <a href="https://play.google.com/apps/testing/dev.danielc.fujiapp"><img src="https://fudge.fm/images/GetItOnGooglePlay_Badge_Web_color_English.png" width=200></a>

## Goals
- Connect to and control a wide range of devices and gadgets - such as earbuds, mirrorless cameras, dashcams, smart TVs, diagnostic systems
- Deep integration with the wireless features of each device, adjusting UI depending on the circumstance
- Serve as a functional alternative to (often privacy-invasive, buggy, or outdated) vendor-provided apps
- Separate the logic between the frontend and reverse-engineered protocol code (similar to [Grayjay](https://grayjay.app/))

## Features
- Material 3 UI with Jetpack Compose
- WiFi and Bluetooth scripting APIs for Android+Linux
- Diverse set of scriptable screens and UI
- Supports many devices out of the box
- Uses new companion APIs to connect - location permission is not required

## Roadmap
- [x] Fujifilm support (libfuji)
- [ ] Canon/Nikon/Sony support (libgphoto2)
- [ ] Dashcam support (top 5 brands)
- [x] QuickJS runtime
- [ ] WebAssembly runtime
- [ ] Sandbox with permissions

## Modules
Reverse-engineered protocol logic is separated from the frontend through a runtime and module system.

Like the Linux kernel module system, modules use low level Bluetooth or WiFi APIs to directly communicate with devices
through their proprietary protocols. Modules can be installed on the fly without recompiling the app.

- Loaded as WebAssembly executable (.wasm) or JS script (QuickJS)
- Connect to a single device through Bluetooth or WiFi
- Directly control and manipulate the UI and respond to user interaction
  - Respond to triggers such as remote shutter, or changing device settings
  - Fill the gallery with metadata/thumbnails
  - Update real time data such as battery, storage, guages
  - Add/remove support for screens, features, and user settings
  - Handle screen navigation and block until device is ready
