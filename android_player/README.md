# Android FHS & MIUI Haptic Player

Minimalistic, high-performance Android player demonstrating nanosecond-precision haptic dispatching and real-time audio latency calibration on MIUI / HyperOS and AOSP devices.

## Highlights
- **Thread & Nano-Clock Dispatcher**: Employs `System.nanoTime()` in a dedicated thread instead of `Handler.postDelayed()`, avoiding Android Choreographer/Looper latency jitter.
- **MIUI RichTap Direct Call**: Uses `performHapticFeedback` with `FLAG_IGNORE_VIEW_SETTING | FLAG_IGNORE_GLOBAL_SETTING` to trigger low-level linear motor ticks.
- **Live Latency Calibration**: Provides interactive `[-20ms, -5ms, 0, +5ms, +20ms]` buttons so users can calibrate against acoustic output latency on the fly.
- **Stand-alone Terminal Build**: Compilable straight from Termux using `aapt`, `javac`, `dx`, and `apksigner`.

## Building the APK

### Requirements
- Java 8+ / OpenJDK 17
- Android build tools (`aapt`, `dx` or `d8`, `apksigner`)
- Android framework jar (`android.jar` and `framework-res.apk`)

### Build Command
```bash
bash build_apk.sh
```
The output APK is generated at `bin/FHSPlayer.apk`.
