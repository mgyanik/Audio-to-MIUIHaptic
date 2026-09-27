# Audio to Haptic Generator (CLI)

High-precision DSP Python tool for translating audio signals into Android vibration arrays and Meta Haptics Studio `.haptic` project files.

## Features
- **FFT Zero-Phase Filter**: Bandpass separation for sub-bass kick transients and mid-range snares.
- **10ms Time Steps**: 100Hz temporal resolution for near-zero sensory lag.
- **Dynamic Adaptive Moving Threshold**: 350ms window tracking standard deviations to isolate real drum attacks from vocal pads.
- **MIUI Hardware Constant Tagging**: Automatically annotates events with MIUI RichTap haptic constants (`0x10000004`, `0x10000000`, `0x10000001`).

## Installation
```bash
pip install -r requirements.txt
```
*(Requires `ffmpeg` installed and available in PATH).*

## Usage
```bash
python3 audio2haptic.py <path_to_audio> [options]
```

### Options
- `-o, --output-dir`: Output folder. Defaults to `<audio_basename>_fhs`.
- `--frame-ms`: Frame step in milliseconds (default: `10`).
- `--sensitivity`: Attack threshold multiplier (default: `1.15`).
- `--min-separation`: Minimum milliseconds between strikes (default: `70`).

## Outputs
- `amplitude.json`: 0-255 vibration amplitudes.
- `timing.json`: Millisecond durations per frame.
- `time_points.json`: Millisecond timestamps.
- `waveform.json`: Complete event queue with hardware tags.
- `waveform_compressed.json`: Run-length encoded waveform.
- `*.haptic`: Meta / Facebook Haptics Studio project file.
