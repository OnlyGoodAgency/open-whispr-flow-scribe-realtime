# sherpa-onnx Android runtime

The Kotlin JNI wrapper and native libraries in this module come from the same
official **sherpa-onnx v1.13.7** Android release. Keep them on one version: JNI
configuration and result objects are marshalled across this boundary, so mixing
wrapper sources and native libraries from different releases can crash inside
`OfflineRecognizer.decode()`.

Release artifact:
`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar`

Artifact SHA-256:
`c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8`

Only the ARM64 and ARMv7 `libonnxruntime.so` and `libsherpa-onnx-jni.so` files
are vendored. The Kotlin files are copied from the `v1.13.7` tag under
`sherpa-onnx/kotlin-api`.
