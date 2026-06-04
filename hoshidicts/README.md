# Rougo HoshiDicts bridge

This Android library module exposes the HoshiDicts dictionary backend to the
Rougo app.

The active native source is not the old root-level bundled copy. Gradle builds
the JNI library through `CMakeLists.txt`, which uses:

- `submodules/hoshidicts`
- `submodules/hoshidicts-kotlin-bridge`
- `src/main/cpp/rougo_hoshi_jni.cpp`

Keep local Rougo-specific Kotlin and JNI wrappers under `src/main/`. Update the
submodules when changing upstream HoshiDicts or bridge code.

The previous copied root-level HoshiDicts source/vendor tree was removed to
avoid maintaining two competing copies.
