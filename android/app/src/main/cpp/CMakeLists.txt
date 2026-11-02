# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Reemplazos de 16 KB para el FFmpeg del motor (M5): libwebp, libwebpmux y libsharpyuv 1.6.0 (BSD-3-Clause),
# la misma versión que trae el paquete de youtubedl-android pero alineada a 16 KB. Salen como libkd_*.so (el
# APK solo empaqueta lib*.so) con el SONAME original; el motor los pone primero en LD_LIBRARY_PATH con su
# nombre de siempre (kd/payload/lib16k) en los celulares con páginas de 16 KB.
cmake_minimum_required(VERSION 3.22)
project(kd_webp C)

if(NOT KD_WEBP_SRC)
  message(FATAL_ERROR "Falta -DKD_WEBP_SRC (la tarea kdWebpSource de app/build.gradle.kts)")
endif()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
foreach(opt WEBP_BUILD_ANIM_UTILS WEBP_BUILD_CWEBP WEBP_BUILD_DWEBP WEBP_BUILD_GIF2WEBP WEBP_BUILD_IMG2WEBP
            WEBP_BUILD_VWEBP WEBP_BUILD_WEBPINFO WEBP_BUILD_WEBPMUX WEBP_BUILD_EXTRAS WEBP_BUILD_FUZZTEST)
  set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)

add_subdirectory(${KD_WEBP_SRC} webp)

foreach(t webp webpmux sharpyuv)
  set_target_properties(${t} PROPERTIES OUTPUT_NAME kd_${t} NO_SONAME ON)
  target_link_options(${t} PRIVATE "-Wl,-soname,lib${t}.so" "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endforeach()
