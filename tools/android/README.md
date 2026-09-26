# Android recording compatibility checks

`slice_arm64_apk.py` removes non-arm64 native libraries from a universal APK. Run
`zipalign -P 16` and `apksigner` after slicing because rewriting the ZIP invalidates
the original APK signature and entry alignment.

Build with the NDK r27 flexible page size option enabled for both native modules.
Validate the **final signed APK**, especially after removing ABIs or repackaging it:

```sh
python tools/android/check_apk_alignment.py path/to/app.apk
zipalign -c -P 16 -v 4 path/to/app.apk
```

The Python check covers every packaged 64-bit native library, including prebuilts.
It checks ELF LOAD segments and uncompressed ZIP entry offsets. Aligning the ZIP
alone cannot repair a library compiled with 4 KB LOAD alignment. Repackaging an
already aligned APK can also break ZIP alignment; align before signing.

These checks do not establish that recording works on a particular device.
Test opening the app, starting/stopping recording, and recording from the keyboard
on both a 4 KB device and a 16 KB device. Check the device page size with:

```sh
adb shell getconf PAGE_SIZE
```

Also test with microphone permission denied, then granted, and with the microphone
unavailable. Startup failures should leave recording idle and show a recovery
message. Capture uses PCM16, normalized to float samples for transcription.

If Samsung reports that the app has a bug, capture the actual crash after reproducing:

```sh
adb logcat -b crash -d > recording-crash.txt
```

For failures handled by the app, use **Settings > Export logs**. The generic Samsung
dialog does not identify the exception, Android version, or device page size.

Reference: [Android 16 KB page size guidance](https://developer.android.com/guide/practices/page-sizes).
