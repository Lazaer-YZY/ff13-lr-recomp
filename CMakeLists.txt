# lr - ReXGlue Recompiled Project
#
# SDK boilerplate lives in generated/rexglue.cmake.

cmake_minimum_required(VERSION 3.25)
project(lr LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(generated/rexglue.cmake)

# Sources
set(LR_SOURCES
    src/main.cpp
    src/lr_hooks.cpp   # guest hooks adapted from leftisthominid/lr-ffxiii-port (GPL-3.0)
)

if(ANDROID)
    # SDL3's SDLActivity loads libmain.so and calls its exported SDL_main (see android/README.md).
    list(APPEND LR_SOURCES
        src/android/android_glue.cpp
    )
    add_library(lr SHARED ${LR_SOURCES})
    set_target_properties(lr PROPERTIES OUTPUT_NAME main PREFIX "lib")
    target_link_options(lr PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-Bsymbolic-functions")
    # Turnip/custom Vulkan driver loading (thirdparty/libadrenotools, BSD-2). Builds the hook libs too.
    add_subdirectory(thirdparty/libadrenotools)
    target_link_libraries(lr PRIVATE adrenotools android log)
    target_compile_definitions(lr PRIVATE LR_HAS_ADRENOTOOLS=1)
    # Android declarations used by the few __ANDROID__ lines in the ported game sources
    # (lr-ffxiii-port's android/game/lr_android.h, adapted; implemented in android_glue.cpp).
    target_compile_options(lr PRIVATE "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/src/android/lr_android.h")
elseif(WIN32)
    add_executable(lr WIN32 ${LR_SOURCES})
else()
    add_executable(lr ${LR_SOURCES})
endif()

rexglue_setup_target(lr)

# Android: the SDK defaults to baseline ARMv8.0. The only target is the Snapdragon 8 Gen 2 (Cortex-X3/A715/A710/A510,
# ARMv9.0): ARMv8.2 + LSE inline atomics (the global lock is taken ~60k times/s), RCpc, dot product, FP16, tuned for
# A715. No SVE: not guaranteed to be enabled by the device kernel. Set LR_ANDROID_MARCH="" to fall back to the SDK default.
if(ANDROID)
    set(LR_ANDROID_MARCH "armv8.2-a+lse+rcpc+dotprod+fp16" CACHE STRING "Android -march for the game and recompiled code")
    if(LR_ANDROID_MARCH)
        foreach(_t lr lr_recomp)
            if(TARGET ${_t})
                target_compile_options(${_t} PRIVATE -march=${LR_ANDROID_MARCH} -mtune=cortex-a715)
            endif()
        endforeach()
    endif()
endif()

target_include_directories(lr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

# Clang PGO for the game library and the recompiled code (Android). LR_PGO=generate builds an
# instrumented library that writes <app files dir>/pgo/lr.profraw every 30 s (android_glue.cpp);
# LR_PGO=<path to a merged .profdata> optimises with it. Empty = off.
set(LR_PGO "" CACHE STRING "Clang PGO: empty, 'generate', or a .profdata path")
if(ANDROID AND LR_PGO)
    if(LR_PGO STREQUAL "generate")
        foreach(_t lr lr_recomp)
            if(TARGET ${_t})
                target_compile_options(${_t} PRIVATE -fprofile-instr-generate)
            endif()
        endforeach()
        target_link_options(lr PRIVATE -fprofile-instr-generate)
        target_compile_definitions(lr PRIVATE LR_PGO_GENERATE=1)
    else()
        foreach(_t lr lr_recomp)
            if(TARGET ${_t})
                target_compile_options(${_t} PRIVATE "-fprofile-instr-use=${LR_PGO}"
                    -Wno-profile-instr-unprofiled -Wno-profile-instr-out-of-date -Wno-profile-instr-missing)
            endif()
        endforeach()
    endif()
endif()

# From lr-ffxiii-port: build the recompiled code for the building PC's CPU (MOVBE, FMA, AVX).
# Default OFF here: the Windows build is only a debug harness and must run on any x86-64 PC.
# -ffp-contract=off keeps float results identical to the SSE4.1 build.
option(LR_NATIVE_CPU "Build the recompiled code for this PC's CPU (x86-64 hosts only)" OFF)
if(LR_NATIVE_CPU AND NOT MSVC AND CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    target_compile_options(lr_recomp PRIVATE -march=native -ffp-contract=off)
    target_compile_options(lr PRIVATE -march=native -ffp-contract=off)
endif()
