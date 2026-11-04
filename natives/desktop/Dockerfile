# Builder for the desktop natives (see README.md); macOS is cross-built with depends' clang + SDK.
# glibc 2.31 is the floor the linux library runs on; mingw is set to posix threads
# exactly as monero's depends CI does.
FROM --platform=linux/amd64 ubuntu:20.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        autoconf automake autotools-dev bison bsdmainutils build-essential bzip2 ca-certificates \
        curl file git gperf libtool make patch pkg-config python3 unzip xz-utils \
        g++-mingw-w64-x86-64 binutils-mingw-w64-x86-64 llvm-10 libtinfo5 \
    && rm -rf /var/lib/apt/lists/* \
    && update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix \
    && update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix

# focal's cmake 3.16 cannot install the macOS cross tools (native_libtapi relink).
ARG CMAKE_VERSION=3.28.6
ARG CMAKE_SHA256=931e3c0d546ee03ca72bb147ccd9b49e3b6252f765f66bf21b9d165519940458
RUN set -eu; curl -fsSL --retry 5 -o /tmp/cmake.tar.gz \
        https://github.com/Kitware/CMake/releases/download/v${CMAKE_VERSION}/cmake-${CMAKE_VERSION}-linux-x86_64.tar.gz; \
    echo "${CMAKE_SHA256}  /tmp/cmake.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/cmake && tar -xzf /tmp/cmake.tar.gz -C /opt/cmake --strip-components=1; rm /tmp/cmake.tar.gz

# Linux JDK: jni.h for linux-x64 and the JVM for the smoke run; Windows JDK: only its jni headers.
ARG JDK_RELEASE=jdk-17.0.20.1%2B1
ARG JDK_LINUX=OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz
ARG JDK_LINUX_SHA256=3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e
ARG JDK_WINDOWS=OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip
ARG JDK_WINDOWS_SHA256=e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0
RUN set -eu; base=https://github.com/adoptium/temurin17-binaries/releases/download/${JDK_RELEASE}; \
    curl -fsSL --retry 5 -o /tmp/jdk.tar.gz "$base/${JDK_LINUX}"; \
    echo "${JDK_LINUX_SHA256}  /tmp/jdk.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/jdk-linux && tar -xzf /tmp/jdk.tar.gz -C /opt/jdk-linux --strip-components=1; \
    curl -fsSL --retry 5 -o /tmp/jdk.zip "$base/${JDK_WINDOWS}"; \
    echo "${JDK_WINDOWS_SHA256}  /tmp/jdk.zip" | sha256sum -c -; \
    unzip -q /tmp/jdk.zip '*/include/*' -d /tmp/jdkw; \
    mkdir -p /opt/jdk-windows && mv /tmp/jdkw/*/include /opt/jdk-windows/include; \
    rm -rf /tmp/jdk.tar.gz /tmp/jdk.zip /tmp/jdkw

ENV PATH=/opt/cmake/bin:/opt/jdk-linux/bin:/usr/lib/llvm-10/bin:$PATH
