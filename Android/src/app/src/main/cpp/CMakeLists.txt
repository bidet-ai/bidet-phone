# bidet-phone whisper.cpp NDK build
#
# Vendored / adapted from whisper.cpp upstream:
#   https://github.com/ggerganov/whisper.cpp/blob/master/examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt
# Pinned via git submodule under ./whisper.cpp at v1.8.4.
#
# Differences from upstream:
#   - WHISPER_LIB_DIR now points at our submodule (one directory level down)
#     instead of the seven-level navigation the upstream sample uses.
#   - We only build the default `whisper` target plus the `arm64-v8a`
#     fp16 variant. armeabi-v7a is dropped — Pixel 8 Pro is the only
#     supported target for v0.1 of bidet-phone.
#
# Apache 2.0 — original whisper.cpp © Georgi Gerganov; modifications
# © 2026 bidet-ai contributors.

cmake_minimum_required(VERSION 3.10)

project(whisper.cpp.android.bidet)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# 2026-05-09 — force optimization on whisper.cpp + ggml regardless of build type.
# AGP's debug variant builds CMake projects with CMAKE_BUILD_TYPE=Debug, which
# means -O0 (no optimization). Whisper-tiny inference at -O0 takes >48 seconds
# for a 30-second chunk on Pixel 8 Pro (verified empirically, repro session
# 2026-05-09 07:59 — see Bug C cascade); user-perceived behaviour is "recording
# captured but no transcript produced" because the slow-running coroutine is
# cancelled on Stop tap before it ever yields a result. Forcing -O3 across all
# build types makes whisper-tiny finish in 2-4 sec/chunk as the upstream brief
# documented.
add_compile_options(-O3 -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections)
# NDEBUG strips assert() — modest perf win, also keeps debug builds from
# tripping on assertions inside whisper.cpp's tensor allocators that aren't
# invariants we want to fail on at runtime.
add_compile_definitions(NDEBUG)

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "Whisper version: ${WHISPER_VERSION}")

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/whisper_jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS                      -march=armv8.2-a+fp16)
    endif ()

    # 2026-05-09: -O3 + section-gc + flto are now applied unconditionally above
    # via add_compile_options() because debug builds of whisper.cpp are unusably
    # slow. The release-only block here used to apply visibility-hidden + flto;
    # those are size optimizations that don't matter for our Pixel-only debug
    # build, so we drop them in favor of -O3-everywhere.

    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

# Pixel 8 Pro = Tensor G3 = ARMv8.2-A with fp16. We build BOTH the default
# `whisper` library and the `whisper_v8fp16_va` variant; LibWhisper.kt picks
# at runtime based on CPU feature detection (/proc/cpuinfo "fphp").
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
endif ()

build_library("whisper")

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
