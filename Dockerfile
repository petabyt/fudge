FROM debian:stable-slim

ARG CMDLINE_TOOLS_VERSION=15859902
ARG NDK_VERSION=29.0.14206865
ARG CMAKE_VERSION=3.31.6

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

RUN <<EOF
set -eux
apt-get update
apt-get install -y --no-install-recommends \
    openjdk-21-jdk-headless \
    wget unzip zip git curl ca-certificates \
    build-essential cmake ninja-build esbuild file xxd jq python3 nasm \
    libusb-1.0-0-dev libexif-dev \
    libncurses6 libstdc++6 lib32stdc++6 lib32z1 \
    locales
echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
locale-gen
rm -rf /var/lib/apt/lists/*
EOF

RUN <<EOF
set -eux
mkdir -p ${ANDROID_HOME}/cmdline-tools
wget -q https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip \
    -O /tmp/cmdline-tools.zip
unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools
mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest
rm /tmp/cmdline-tools.zip
EOF

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

RUN <<EOF
set -eux
yes | sdkmanager --licenses >/dev/null
sdkmanager --update
sdkmanager \
    "platform-tools" \
    "emulator" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "build-tools;37.0.0" \
    "ndk;${NDK_VERSION}" \
    "cmake;${CMAKE_VERSION}" \
    "extras;android;m2repository" \
    "extras;google;m2repository"
sdkmanager "platforms;android-37" || sdkmanager "platforms;android-37.0"
EOF

ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${NDK_VERSION} \
    PATH=${PATH}:${ANDROID_HOME}/cmake/${CMAKE_VERSION}/bin:${ANDROID_NDK_HOME}

RUN <<EOF
set -eux
rm -rf ${ANDROID_HOME}/.android /tmp/* /var/tmp/*
find ${ANDROID_HOME} -name "*.zip" -delete 2>/dev/null || true
EOF

WORKDIR /project
CMD ["sdkmanager", "--list_installed"]
