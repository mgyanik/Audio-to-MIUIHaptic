#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Audio to Android Haptic Waveform Generator (High-Precision 10ms Engine)
Features:
- Sub-frame transient inflection detection (removes envelope phase lag)
- 10ms (100Hz) temporal resolution
- Outputs exact timestamped beat event queue for zero-jitter Android playback
- Generates standard Android VibrationEffect arrays and Meta Haptics Studio .haptic
"""

import os
import sys
import io
import json
import wave
import shutil
import argparse
import subprocess
import numpy as np

# MIUI 硬件触觉常量
MIUI_TAP_NORMAL   = 0x10000000 # 268435456 清脆标准点按
MIUI_TAP_LIGHT    = 0x10000001 # 268435457 极轻脆微动
MIUI_SWITCH       = 0x10000003 # 268435459 机械开关微弹
MIUI_MESH_HEAVY   = 0x10000004 # 268435460 沉稳重刻度顿挫 (底鼓)
MIUI_MESH_LIGHT   = 0x10000006 # 268435462 细颗粒滴答
MIUI_POPUP_NORMAL = 0x10000008 # 268435464 饱满清脆弹窗


def check_ffmpeg():
    """Ensure ffmpeg is installed and accessible in PATH."""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is not found in PATH.")


def decode_audio(input_file: str, target_sr: int = 24000) -> tuple[np.ndarray, int]:
    """Decodes audio to mono 16-bit PCM at target_sr (default: 24kHz for crisp transients)."""
    check_ffmpeg()
    cmd = [
        "ffmpeg", "-v", "error", "-y",
        "-i", input_file, "-vn",
        "-acodec", "pcm_s16le", "-ac", "1",
        "-ar", str(target_sr), "-f", "wav", "pipe:1"
    ]
    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout_data, stderr_data = process.communicate()

    if process.returncode != 0:
        raise RuntimeError(f"FFmpeg decoding failed: {stderr_data.decode(errors='replace')}")

    with wave.open(io.BytesIO(stdout_data), "rb") as wf:
        n_frames = wf.getnframes()
        sr = wf.getframerate()
        raw_bytes = wf.readframes(n_frames)

    pcm_int16 = np.frombuffer(raw_bytes, dtype=np.int16)
    pcm_float = pcm_int16.astype(np.float32) / 32768.0
    return pcm_float, sr


def butterworth_bandpass_fft(signal: np.ndarray, sr: int, low_cut: float, high_cut: float) -> np.ndarray:
    """Zero-phase bandpass filter using FFT frequency-domain filtering."""
    n = len(signal)
    if n == 0:
        return signal

    fft_vals = np.fft.rfft(signal)
    freqs = np.fft.rfftfreq(n, d=1.0 / sr)

    with np.errstate(divide='ignore'):
        hp_response = 1.0 / np.sqrt(1.0 + (low_cut / np.maximum(freqs, 1e-6)) ** 4)
    hp_response[freqs == 0] = 0.0

    lp_response = 1.0 / np.sqrt(1.0 + (freqs / high_cut) ** 8)

    filter_h = hp_response * lp_response
    filtered_signal = np.fft.irfft(fft_vals * filter_h, n=n)
    return filtered_signal.astype(np.float32)


def extract_rms_envelope(signal: np.ndarray, sr: int, frame_ms: int = 10) -> np.ndarray:
    """Computes RMS energy with tight window (1.5x hop) for minimum group delay."""
    hop_length = int(sr * frame_ms / 1000.0)
    win_length = int(hop_length * 1.5)

    if len(signal) < win_length:
        signal = np.pad(signal, (0, win_length - len(signal)))

    n_frames = int(np.ceil(len(signal) / hop_length))
    pad_len = (n_frames * hop_length + win_length) - len(signal)
    padded = np.pad(signal, (0, max(0, pad_len))) if pad_len > 0 else signal

    window = np.hanning(win_length).astype(np.float32)
    norm_w = np.sqrt(np.mean(window ** 2))
    if norm_w > 0:
        window = window / norm_w

    rms_list = []
    for i in range(n_frames):
        start = i * hop_length
        frame = padded[start : start + win_length] * window
        rms_val = np.sqrt(np.mean(frame ** 2))
        rms_list.append(rms_val)

    return np.array(rms_list, dtype=np.float32)


def extract_precision_beats(
    pcm: np.ndarray,
    sr: int,
    frame_ms: int = 10,
    sensitivity: float = 1.15,
    min_separation_ms: int = 70
) -> tuple[list[dict], list[int], list[int], list[int]]:
    """
    Sub-frame transient detection:
    - Analyzes sub-bass (35-125Hz) and rhythm-transient (125-350Hz)
    - Detects onset inflection point (where attack starts) rather than peak delay
    - Assigns appropriate MIUI hardware constants
    """
    bass = butterworth_bandpass_fft(pcm, sr, low_cut=35.0, high_cut=125.0)
    mid = butterworth_bandpass_fft(pcm, sr, low_cut=125.0, high_cut=350.0)

    rms_b = extract_rms_envelope(bass, sr, frame_ms=frame_ms)
    rms_m = extract_rms_envelope(mid, sr, frame_ms=frame_ms)

    diff_b = np.maximum(0.0, np.diff(rms_b, prepend=rms_b[0]))
    diff_m = np.maximum(0.0, np.diff(rms_m, prepend=rms_m[0]))

    onset_signal = diff_b + 0.35 * diff_m
    n = len(onset_signal)

    # Adaptive moving threshold (350ms window)
    window = int(350 / frame_ms)
    local_mean = np.convolve(onset_signal, np.ones(window)/window, mode="same")
    local_std = np.sqrt(np.maximum(0, np.convolve(onset_signal**2, np.ones(window)/window, mode="same") - local_mean**2))

    thresh = local_mean + sensitivity * local_std
    abs_floor = 0.003 # noise floor

    min_sep_frames = max(1, int(min_separation_ms / frame_ms))
    events = []
    amps = np.zeros(n, dtype=int)
    last_p = -min_sep_frames

    # Detect raw peak candidates
    peak_frames = []
    for i in range(2, n - 2):
        if onset_signal[i] > thresh[i] and onset_signal[i] > abs_floor:
            if onset_signal[i] >= onset_signal[i-1] and onset_signal[i] >= onset_signal[i+1]:
                if i - last_p >= min_sep_frames:
                    peak_frames.append(i)
                    last_p = i

    if peak_frames:
        top_val = np.percentile(onset_signal[peak_frames], 96)
        if top_val <= 0:
            top_val = np.max(onset_signal[peak_frames])

        for pf in peak_frames:
            # Inflection correction: find the exact attack front (1 frame earlier if steep)
            af = pf
            if onset_signal[pf - 1] > thresh[pf - 1] * 0.65:
                af = pf - 1

            time_ms = int(af * frame_ms)
            rel = min(1.0, onset_signal[pf] / top_val)

            # Three-tier amplitude & hardware mapping
            if rel >= 0.65:
                # 重鼓 / 808 / 下潜重低音
                a0 = int(round(180 + ((rel - 0.65) / 0.35) * 75))
                miui_code = MIUI_MESH_HEAVY
                btype = "heavy"
            elif rel >= 0.25:
                # 军鼓 / 次重拍
                a0 = int(round(90 + ((rel - 0.25) / 0.40) * 85))
                miui_code = MIUI_TAP_NORMAL
                btype = "medium"
            else:
                # 柔和轻拍 / 吉他拨弦微瞬态
                a0 = int(round(40 + (rel / 0.25) * 45))
                miui_code = MIUI_TAP_LIGHT
                btype = "light"

            events.append({
                "time_ms": time_ms,
                "amplitude": a0,
                "type": btype,
                "miui_haptic": miui_code
            })

            amps[af] = a0
            # 极速脉冲衰减 (20-30ms)
            if af + 1 < n:
                amps[af + 1] = int(round(a0 * 0.35))
            if af + 2 < n and a0 >= 180:
                amps[af + 2] = int(round(a0 * 0.12))

    durations_ms = [int(frame_ms)] * n
    timestamps_ms = [int(i * frame_ms) for i in range(n)]
    int_amps = [int(x) for x in amps]

    return events, int_amps, durations_ms, timestamps_ms


def compress_waveform(amplitudes: list[int], timings: list[int]) -> tuple[list[int], list[int], list[int]]:
    """Run-Length Compression."""
    if not amplitudes:
        return [], [], []

    comp_amps = []
    comp_durations = []
    comp_timestamps = []

    curr_amp = amplitudes[0]
    curr_dur = timings[0]
    curr_time = 0

    for a, t in zip(amplitudes[1:], timings[1:]):
        if a == curr_amp:
            curr_dur += t
        else:
            comp_amps.append(int(curr_amp))
            comp_durations.append(int(curr_dur))
            comp_timestamps.append(int(curr_time))
            curr_time += curr_dur
            curr_amp = a
            curr_dur = t

    comp_amps.append(int(curr_amp))
    comp_durations.append(int(curr_dur))
    comp_timestamps.append(int(curr_time))

    return comp_amps, comp_durations, comp_timestamps


def export_meta_haptic(amplitudes: list[int], frame_ms: int, source_name: str) -> dict:
    """Exports Facebook/Meta Haptics Studio .haptic JSON format."""
    amp_envelope = []
    for i, amp in enumerate(amplitudes):
        t_sec = round(i * frame_ms / 1000.0, 4)
        norm_val = round(amp / 255.0, 4)
        amp_envelope.append({"time": t_sec, "amplitude": norm_val})

    total_sec = round(len(amplitudes) * frame_ms / 1000.0, 4)
    if amp_envelope and amp_envelope[-1]["amplitude"] > 0:
        amp_envelope.append({"time": total_sec, "amplitude": 0.0})

    haptic_json = {
        "version": {"major": 1, "minor": 0, "patch": 0},
        "metadata": {
            "creator": "Audio2Haptic Precision 10ms Engine",
            "source": source_name,
            "frame_rate_hz": round(1000.0 / frame_ms, 1)
        },
        "signals": {
            "continuous": {
                "envelopes": {
                    "amplitude": amp_envelope,
                    "frequency": [
                        {"time": 0.0, "frequency": 0.5}
                    ]
                }
            }
        }
    }
    return haptic_json


def process_audio_file(
    input_file: str,
    output_dir: str = None,
    frame_ms: int = 10,
    sensitivity: float = 1.15,
    min_separation_ms: int = 70
) -> str:
    """Full pipeline processing for a single audio file."""
    if not os.path.isfile(input_file):
        raise FileNotFoundError(f"Input file not found: {input_file}")

    base_name = os.path.splitext(os.path.basename(input_file))[0]
    if output_dir is None:
        output_dir = os.path.join(os.path.dirname(os.path.abspath(input_file)), f"{base_name}_fhs")
    os.makedirs(output_dir, exist_ok=True)

    print(f"\n=======================================================")
    print(f"🎵 FHS Audio Haptics (High-Precision 10ms Engine)")
    print(f"=======================================================")
    print(f"[1/3] Decoding audio with ffmpeg: {os.path.basename(input_file)} (24kHz Mono)")
    pcm, sr = decode_audio(input_file, target_sr=24000)
    duration_sec = len(pcm) / sr
    mins = int(duration_sec // 60)
    secs = duration_sec % 60
    print(f"      Duration: {mins:02d}:{secs:05.2f} ({duration_sec:.2f}s), Samples: {len(pcm)}, SR: {sr}Hz")

    print(f"[2/3] Inflection-Corrected Transient Extraction ({frame_ms}ms step)")
    events, amplitudes, durations, timestamps = extract_precision_beats(
        pcm, sr, frame_ms=frame_ms, sensitivity=sensitivity, min_separation_ms=min_separation_ms
    )
    comp_amps, comp_durations, comp_timestamps = compress_waveform(amplitudes, durations)

    np_amps = np.array(amplitudes)
    zero_ratio = np.mean(np_amps == 0) * 100
    heavy_cnt = sum(1 for e in events if e["type"] == "heavy")
    mid_cnt = sum(1 for e in events if e["type"] == "medium")
    light_cnt = sum(1 for e in events if e["type"] == "light")

    print(f"      Precision Timing Stats:")
    print(f"      • 高精度节拍事件总数   : {len(events)} 个 (毫秒对齐事件队列)")
    print(f"      • 空闲停顿比例 (Silence) : {zero_ratio:.1f}%")
    print(f"      • 重低音事件 (180-255)   : {heavy_cnt} 个 (底鼓/0x04 MESH_HEAVY)")
    print(f"      • 军鼓中击点 (90-180)    : {mid_cnt} 个 (常规拍/0x00 TAP_NORMAL)")
    print(f"      • 前奏轻触点 (40-90)     : {light_cnt} 个 (拨弦/0x01 TAP_LIGHT)")

    print(f"[3/3] Writing output files to: {output_dir}")

    # 1. amplitude.json
    with open(os.path.join(output_dir, "amplitude.json"), "w", encoding="utf-8") as f:
        json.dump(amplitudes, f, indent=None)

    # 2. timing.json
    with open(os.path.join(output_dir, "timing.json"), "w", encoding="utf-8") as f:
        json.dump(durations, f, indent=None)

    # 3. time_points.json
    with open(os.path.join(output_dir, "time_points.json"), "w", encoding="utf-8") as f:
        json.dump(timestamps, f, indent=None)

    # 4. waveform.json (内含 events 毫秒队列与 10ms 步进波形)
    waveform_data = {
        "format": "android_waveform_precision",
        "audio_source": os.path.basename(input_file),
        "frame_ms": frame_ms,
        "sample_count": len(amplitudes),
        "event_count": len(events),
        "total_duration_ms": len(amplitudes) * frame_ms,
        "events": events,
        "amplitudes": amplitudes,
        "timings": durations,
        "time_points_ms": timestamps
    }
    with open(os.path.join(output_dir, "waveform.json"), "w", encoding="utf-8") as f:
        json.dump(waveform_data, f, indent=2)

    # 5. waveform_compressed.json
    comp_data = {
        "format": "android_waveform_compressed",
        "audio_source": os.path.basename(input_file),
        "frame_ms": frame_ms,
        "step_count": len(comp_amps),
        "total_duration_ms": sum(comp_durations),
        "amplitudes": comp_amps,
        "timings": comp_durations,
        "time_points_ms": comp_timestamps
    }
    with open(os.path.join(output_dir, "waveform_compressed.json"), "w", encoding="utf-8") as f:
        json.dump(comp_data, f, indent=2)

    # 6. meta_haptic.haptic
    meta_haptic_data = export_meta_haptic(amplitudes, frame_ms, os.path.basename(input_file))
    meta_file = os.path.join(output_dir, f"{base_name}.haptic")
    with open(meta_file, "w", encoding="utf-8") as f:
        json.dump(meta_haptic_data, f, indent=2)

    print("\n✅ Conversion Successful (10ms Precision)!")
    print(f"  • Source Track         : {os.path.basename(input_file)}")
    print(f"  • Output Directory     : {output_dir}")
    print(f"=======================================================\n")
    return output_dir


def main():
    parser = argparse.ArgumentParser(
        description="Audio to Android Haptic Waveform Generator (High-Precision 10ms Engine)"
    )
    parser.add_argument("input", help="Path to input audio file")
    parser.add_argument("-o", "--output-dir", help="Output directory")
    parser.add_argument("--frame-ms", type=int, default=10, help="Frame step duration in ms (default: 10)")
    parser.add_argument("--sensitivity", type=float, default=1.15, help="Beat sensitivity threshold (default: 1.15)")
    parser.add_argument("--min-separation", type=int, default=70, help="Minimum beat separation in ms (default: 70)")

    args = parser.parse_args()

    process_audio_file(
        input_file=args.input,
        output_dir=args.output_dir,
        frame_ms=args.frame_ms,
        sensitivity=args.sensitivity,
        min_separation_ms=args.min_separation
    )


if __name__ == "__main__":
    main()
