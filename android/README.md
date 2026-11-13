# Lightning Returns Recomp - Android app

Single Gradle module `:app` (`com.lrrecomp.android`). arm64-v8a only, minSdk 26,
compile/target SDK 35, AGP 8.9.3, Gradle 8.11.1 (SHA-256 pinned), JDK 17, NDK 28.2.13676358.
GPL-3.0.

## Build

Java side only (native libraries not required yet):

    source /c/lr/tools/env.sh
    ./gradlew --no-daemon --console=plain :app:assembleDebug -PlrSkipNativeCheck=true

Full build: stage the prebuilt native libraries (separate script, to be written) into
`app/src/main/jniLibs/arm64-v8a/`, then `./gradlew :app:assembleDebug`. `checkNativeLibraries`
fails `assemble*` with a clear message if any required library is missing.
Version: `-PlrVersionName=v0.8.0` -> versionName 0.8.0, versionCode 80000 (default 0.1.0).
`local.properties` needs `sdk.dir=C:/dev/android-sdk` (forward slashes). Both build types are
debug-signed (no release keystore; set `LR_ANDROID_DEBUG_KEYSTORE` for a stable key).
The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Expected jniLibs (arm64-v8a)

Required: `libmain.so`, `libSDL3.so`, `libc++_shared.so`, `librexruntime.so`, `librexgpu-xenos.so`.
Optional (adrenotools custom-driver loader): `libhook_impl.so`, `libmain_hook.so`,
`libfile_redirect_hook.so`, `libgsl_alloc_hook.so`. Libraries are extracted at install
(`useLegacyPackaging = true`) because adrenotools opens its hook libs by path.
Load order (RuntimeActivity.getLibraries): c++_shared, SDL3, rexruntime, rexgpu-xenos, main.

## SDL Java shim

`sdl-java.gradle` syncs `sdk/rexglue-sdk/thirdparty/sdl3/android-project/.../java` (SDL 3.4.14)
into `app/build/generated/sdl-java` and patches `HIDDeviceManager`'s USB receiver (null-safe
intent, permission re-check). The build fails if the patch anchor ever goes missing.

## Native contract

argv (ReXGlue cvars, `--name=value`):

    --game_data_root=<game folder>      (default <external files>/game, must hold default.xex + weiss_data/)
    --log_file=<external files>/logs/lr.log
    --gpu_plugin=xenos

Environment (set via SDL `nativeSetenv` before the native main starts):

| Variable | Value |
|---|---|
| `LR_NATIVE_LIB_DIR` | nativeLibraryDir with trailing `/` |
| `LR_CUSTOM_DRIVER_DIR` | selected GPU driver folder, trailing `/` (unset = system driver) |
| `LR_VK_CUSTOM_DRIVER` | driver `.so` name from the package metadata |
| `LR_VK_CUSTOM_DRIVER_MODE` | `adrenotools` or `icd` |
| `LR_LOG_DIR` | `<external files>/logs` |
| `LR_ANDROID_DEVICE` | one-line device description (extra) |
| `LR_VK_ADRENO650_SYNC_DRAW` | `1` on Adreno 650 with Turnip (extra) |
| `MESA_SHADER_CACHE_DIR`, `MESA_SHADER_CACHE_MAX_SIZE` | `<cache>/mesa_shader_cache`, `256M` |
| `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER` | `1` with PanVK |

Debug builds also forward any `LR_*` intent extra (`am start --es LR_FOO 1`) and accept a
`game_root` string extra.

JNI (class `com.lrrecomp.android.RuntimeActivity`, symbols
`Java_com_lrrecomp_android_RuntimeActivity_*`), implemented natively later:
`static native void nativeSetTouchInput(int buttons, int leftTrigger, int rightTrigger, int leftX, int leftY, int rightX, int rightY)` and
`static native boolean nativeHasConnectedController()`. The native side may also call
`void reportGraphicsFailure(String)`. `SDL_SendAndroidMessage(0x8000)` opens the game-folder page.

## Adapted from Lost Odyssey Recomp (GPL-3.0)

Java sources, manifest, styles, lint config, Gradle files are adapted from
`packaging/android` of Lost Odyssey Recomp with renames (LO -> LR). Changes: no probe module,
no externalNativeBuild, no DXC/FFmpeg/audio-license staging; game folder is `game/default.xex`
(no disc1..4); ISO/DLC importer, `SaveLocation*`/`SaveTransfer*` and translated strings removed
(English only). TODO: saves/logs export page (PlayerLogs ZIP export exists but has no UI),
ISO/DLC import, translations, real launcher icon (placeholder vector bolt).
