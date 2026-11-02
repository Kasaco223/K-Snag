# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Compila K-Snag para Android en Linux (Docker Desktop + WSL2) desde una PC con Windows y Smart App
# Control: aquí no corre ningún .exe ni .dll sin firmar (el aapt2 de Maven, las DLL nativas de Gradle).
# Uso: android/README.md › Compilar con Docker.
FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# rsync y git los usa kdl-build.sh; python3, los scripts de android/scripts.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip rsync git python3 \
 && rm -rf /var/lib/apt/lists/*

# El SDK de Android (cmdline-tools, plataforma y build-tools) se distribuye con el «Android Software
# Development Kit License Agreement» (https://developer.android.com/studio/terms). Descargarlo e instalarlo
# implica aceptarlo, y eso lo decide quien construye la imagen: compose.yaml pasa este argumento desde la
# variable KDL_ACCEPT_ANDROID_SDK_LICENSE. Sin "yes", la imagen no se construye y no se baja nada del SDK.
ARG ACCEPT_ANDROID_SDK_LICENSE=""
RUN if [ "$ACCEPT_ANDROID_SDK_LICENSE" != "yes" ]; then \
      echo "Para construir esta imagen tienes que leer y aceptar la licencia del SDK de Android:" >&2; \
      echo "  https://developer.android.com/studio/terms  (android/README.md › Compilar con Docker)" >&2; \
      exit 1; \
    fi

ARG CLT_BUILD=16111833
ARG CLT_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:${PATH}

# cmdline-tools 23.0 (repository2-3.xml), verificado con el SHA-1 que publica Google.
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSLo /tmp/clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CLT_BUILD}_latest.zip" \
 && echo "${CLT_SHA1}  /tmp/clt.zip" | sha1sum -c - \
 && unzip -q /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools" \
 && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
 && rm /tmp/clt.zip

# Los mismos paquetes que SDK_PACKAGES en .github/workflows/android-ci.yml: compileSdk 37
# (platforms;android-37.0) y build-tools 36.0.0 (el mínimo y el predeterminado de AGP 9.4.1). Solo se
# responden las licencias de estos paquetes. El NDK y CMake compilan la libwebp propia de 16 KB (app/src/main/cpp).
RUN (yes || true) | sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0" "ndk;29.0.14206865" "cmake;3.31.5" > /dev/null \
 && rm -rf /root/.android/cache

COPY kdl-build.sh /usr/local/bin/kdl-build
RUN chmod 0755 /usr/local/bin/kdl-build
WORKDIR /work/android
ENTRYPOINT ["/usr/local/bin/kdl-build"]
CMD [":app:assembleGithubDebug"]
