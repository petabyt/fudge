
<div align="right">
  <details>
    <summary >🌐 Language</summary>
    <div>
      <div align="center">
        <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=en">English</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=zh-CN">简体中文</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=zh-TW">繁體中文</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=ja">日本語</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=ko">한국어</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=hi">हिन्दी</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=th">ไทย</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=fr">Français</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=de">Deutsch</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=es">Español</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=it">Italiano</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=ru">Русский</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=pt">Português</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=nl">Nederlands</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=pl">Polski</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=ar">العربية</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=fa">فارسی</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=tr">Türkçe</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=vi">Tiếng Việt</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=id">Bahasa Indonesia</a>
        | <a href="https://openaitx.github.io/view.html?user=petabyt&project=fudge&lang=as">অসমীয়া</
      </div>
    </div>
  </details>
</div>

# Fudge
This is a cross-platform open-source alternative to Fujifilm's official camera app.  

[Website](https://fudge.danielc.dev/) | [Google Play](https://play.google.com/store/apps/details?id=dev.danielc.fujiapp) | [F-Droid](https://apt.izzysoft.de/fdroid/index/apk/dev.danielc.fujiapp)

<img src='fastlane/metadata/android/en-US/images/phoneScreenshots/1.png' width='250'><img src='fastlane/metadata/android/en-US/images/phoneScreenshots/2.png' width='250'><img src='fastlane/metadata/android/en-US/images/phoneScreenshots/4.png' width='250'>

This app isn't finished yet, so don't set high expectations. Fuji's implementation of PTP/IP has many different quirks and bugs, so it's not easy to create a stable client that works for all cameras.

## Roadmap
- Frontend rewrite (see https://github.com/petabyt/fudge/issues/26)
- Liveview & Remote capture
- Implement Bluetooth pairing

## Compiling
```
git clone https://github.com/petabyt/fudge.git --depth 1 --recurse-submodules
```

### Compiling Android
Open android/ in Android Studio.

### Compiling desktop app
Desktop utility is still a work in progress.
```
# Ubuntu/Debian deps:
sudo apt install cmake ninja-build libgtk-3-dev libusb-1.0-0-dev libvulkan-dev libglfw3-dev
```
```
cd desktop
cmake -B build -G Ninja && cmake --build build
```
MinGW/osxcross is also supported for cross compiling.

# Credits
[libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) (IJG License, Modified (3-clause) BSD License)  
This software is based in part on the work of the Independent JPEG Group.

[ezxml](https://ezxml.sourceforge.net/) (MIT License)

[lua 5.3](https://www.lua.org/license.html) (MIT License)

[com.jsibbold:zoomage](https://github.com/jsibbold/zoomage/blob/master/LICENSE)
