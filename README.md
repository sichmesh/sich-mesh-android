# SICH Mesh for Android

The Android app for devices of the **SICH** mesh network ([sichmesh.org](https://sichmesh.org)).
(The iPhone app will be its own project, `sich-mesh-ios`.)

Version 0.1:

- finds a SICH device over Bluetooth LE and pairs with it using the 6-digit code shown on the device;
- shows the device's state (ORLYK, LoRa, GPS, firmware);
- carries **ORLYK** (the anonymous chat over the internet) through the phone when the device has no
  Wi-Fi: the phone passes sealed frames of a fixed size to the relay and back. It never sees messages,
  contacts or keys: they stay on the device;
- keeps working with the screen off (a foreground service with a notification), and reconnects by itself.

More of the device (ORLYK chat, LoRa chat, nodes, map, settings) comes step by step.

The app uses the Android SDK only: no third-party libraries run inside it.

## How it talks to the device

Bluetooth LE, one GATT service, messages cut into chunks: see `docs/phone-link.md` in the SICH firmware.
The app calls the same API the device's web panel uses. `link/Chunks.kt` follows the same rules as the
device code; `app/src/test/resources/vectors.txt` holds chunks made by the device code, and the unit
tests check the app against them.

## Build

JDK 17 and the Android SDK (Android Studio has both).

```sh
./gradlew testDebugUnitTest      # the tests
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

GitHub Actions builds the same APK on every push (`.github/workflows/build.yml`). For now it is signed
with the debug key: for testing only. Official releases will be signed with the SICH release key; its
fingerprint will be published on sichmesh.org so anyone can check an APK is genuine. The key itself is
never put in this repository.

## Licence

GPL-3.0 (see `LICENSE`). The SICH name and the trident logo (the app icon) are trademarks of
BFU Electronics LTD and are not covered by the licence: see [TRADEMARK.md](TRADEMARK.md).
