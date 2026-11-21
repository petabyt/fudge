# Fudge

This is an app that can connect to Fujifilm cameras and various other cameras and devices, serving as an alternative to the vendor-provided app.

This is a rewrite and continuation of the [Android app](https://fudge.fm/blog/fujiapp/) started in 2023.

| <img src='https://s1.danielc.dev/screenshots/Screenshot_20260128-140111.png' width='350'> | <img src='https://s1.danielc.dev/screenshots/Screenshot_20260430-204144.png' width='350'> | <img src='https://s1.danielc.dev/screenshots/Screenshot_20260430-205138.png' width='350'> | <img src='https://s1.danielc.dev/screenshots/Screenshot_20260804-005001.png' width='350'> |
|-|-|-|-|

Install Beta builds:  
<a href="https://github.com/petabyt/fudge/releases/tag/latest"><img src="https://fudge.fm/images/badge_obtainium.png" width=200></a> <a href="https://play.google.com/apps/testing/dev.danielc.fujiapp"><img src="https://fudge.fm/images/GetItOnGooglePlay_Badge_Web_color_English.png" width=200></a>

## Goals
- Connect to and control a wide range of devices and gadgets - such as earbuds, mirrorless cameras, dashcams, smart TVs, diagnostic systems
- Deep integration with the wireless features of each device, adjusting UI depending on the featureset
- Serve as a functional alternative to (often privacy-invasive, buggy, or outdated) vendor-provided apps
- Separate the logic between the frontend and reverse-engineered protocol code

## Features
- Fujifilm support with [libfuji](https://github.com/petabyt/libfuji)
- Download and manage photos and videos from your cameras
- Material 3 UI with Jetpack Compose
- WiFi, Bluetooth, and USB scripting APIs
- Diverse set of scriptable screens and UI
- Uses modern Android companion APIs to connect; more seamless and no location permissions required

## Roadmap
- [x] Fujifilm support (libfuji)
- [ ] Canon/Nikon/Sony support (libgphoto2)
- [ ] Dashcam support (top 5 brands)
- [x] QuickJS runtime
- [x] WebAssembly runtime
- [ ] WASM/JS Sandbox with permissions
- [ ] iOS build

## Tech stack
- Kotlin Multiplatform for backend and view models
- Compose Multiplatform for UI
- libjpeg-turbo and rtsp-client-android for liveviews
- JNI glue code (ndk/) written in C
- JS runtime based on [QuickJS](https://github.com/bellard/quickjs), WASM runtime based on [WAMR](https://github.com/wasm-micro-runtime/wasm-micro-runtime); bindings implemented in https://github.com/petabyt/libpak

## Modular Architecture
Reverse-engineered protocol logic is separated from the frontend through a runtime and module system.

Like the Linux kernel module system, modules use low level Bluetooth or WiFi APIs to directly communicate with devices
through their proprietary protocols. Modules can be installed on the fly without recompiling the app.

Modules can be loaded into the app as WebAssembly executablse (.wasm) or JS scripts (QuickJS). Using a passive manifest-based
discovery system, modules are initialized only when a compatible device is enumerated and ready to connect.

![Architecture Diagram](https://fudge.fm/images/graph.png)

The specification for the module API is available here: https://github.com/petabyt/libpak  
The current set of modules are available here: https://github.com/petabyt/gadget-libs  

APIs exposed to guest modules:
- Bluetooth Low Energy and Classic APIs
- POSIX socket interface
- WiFi Access Point API
- Widget API - Create, update, and delete widgets that can be displayed on the app's UI
- File gallery API - Fill the app's gallery with images; manage folders; storage devices
- Screen API - Handle switching between different screens such as liveview, gallery, or intervalometer

## Alternative clients and modules

Because of the modular architecture, both Fudge can be swapped out for an alternative client
and libfuji can be swapped out for an alternative Fujifilm pairing library. The module API is well defined enough
that the exact WASM or JS files can be copied from one client to another and run without modifications.

There is already a demo web Fudge client that runs unmodified libfuji WASM builds: https://github.com/petabyt/fudge-www
