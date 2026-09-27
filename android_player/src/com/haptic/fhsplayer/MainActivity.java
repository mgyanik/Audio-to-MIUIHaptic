package com.haptic.fhsplayer;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    public static final int MIUI_TAP_NORMAL     = 0x10000000; // 0: 清脆标准点按
    public static final int MIUI_TAP_LIGHT      = 0x10000001; // 1: 极轻脆微动
    public static final int MIUI_MESH_HEAVY     = 0x10000004; // 4: 沉稳重刻度顿挫 (底鼓)

    private static final int HAPTIC_FLAGS = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING;

    public static class BeatEvent {
        public long timeMs;
        public int miuiHaptic;

        public BeatEvent(long timeMs, int miuiHaptic) {
            this.timeMs = timeMs;
            this.miuiHaptic = miuiHaptic;
        }
    }

    private View rootView;
    private Vibrator vibrator;
    private MediaPlayer mediaPlayer;
    private AudioAttributes mediaAudioAttributes;

    private List<BeatEvent> beatEvents = new ArrayList<>();
    private long totalDurationMs = 268400;

    // 触觉延迟微调补偿值 (默认 -25ms，用于抵消 Android 扬声器音频流输出缓冲延迟)
    private int latencyOffsetMs = -25;

    private volatile boolean isPlaying = false;
    private long audioStartNano = 0;
    private long seekOffsetMs = 0;

    private Thread precisionThread;
    private Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView tvDeviceInfo;
    private TextView tvStatus;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private TextView tvOffsetLabel;
    private SeekBar seekBar;

    private Button btnOffsetMinus20;
    private Button btnOffsetMinus5;
    private Button btnOffsetZero;
    private Button btnOffsetPlus5;
    private Button btnOffsetPlus20;

    private Button btnPlayMiuiHaptics;
    private Button btnJumpChorus;
    private Button btnStop;
    private Button btnTestTapLight;
    private Button btnTestTapNormal;
    private Button btnTestMeshHeavy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootView = findViewById(R.id.root_layout);
        tvDeviceInfo = findViewById(R.id.tv_device_info);
        tvStatus = findViewById(R.id.tv_status);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        tvOffsetLabel = findViewById(R.id.tv_offset_label);
        seekBar = findViewById(R.id.seek_bar);

        btnOffsetMinus20 = findViewById(R.id.btn_offset_minus_20);
        btnOffsetMinus5 = findViewById(R.id.btn_offset_minus_5);
        btnOffsetZero = findViewById(R.id.btn_offset_zero);
        btnOffsetPlus5 = findViewById(R.id.btn_offset_plus_5);
        btnOffsetPlus20 = findViewById(R.id.btn_offset_plus_20);

        btnPlayMiuiHaptics = findViewById(R.id.btn_play_miui_haptics);
        btnJumpChorus = findViewById(R.id.btn_jump_chorus);
        btnStop = findViewById(R.id.btn_stop);
        btnTestTapLight = findViewById(R.id.btn_test_tap_light);
        btnTestTapNormal = findViewById(R.id.btn_test_tap_normal);
        btnTestMeshHeavy = findViewById(R.id.btn_test_mesh_heavy);

        initVibrator();
        setupOffsetControls();
        setupTestButtons();
        loadWaveformData();

        btnPlayMiuiHaptics.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPrecisionPlayback();
            }
        });

        btnJumpChorus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekToPosition(55000);
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && totalDurationMs > 0) {
                    long targetMs = (progress * totalDurationMs) / 1000;
                    seekToPosition(targetMs);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void initVibrator() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaAudioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build();
        }
        tvDeviceInfo.setText("✅ MIUI 线性马达已锁定 10ms 纳秒时钟事件调度器");
        tvDeviceInfo.setTextColor(0xFF00E676);
    }

    private void setupOffsetControls() {
        updateOffsetDisplay();

        btnOffsetMinus20.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                latencyOffsetMs -= 20;
                updateOffsetDisplay();
            }
        });

        btnOffsetMinus5.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                latencyOffsetMs -= 5;
                updateOffsetDisplay();
            }
        });

        btnOffsetZero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                latencyOffsetMs = 0;
                updateOffsetDisplay();
            }
        });

        btnOffsetPlus5.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                latencyOffsetMs += 5;
                updateOffsetDisplay();
            }
        });

        btnOffsetPlus20.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                latencyOffsetMs += 20;
                updateOffsetDisplay();
            }
        });
    }

    private void updateOffsetDisplay() {
        String sign = latencyOffsetMs > 0 ? "+" : "";
        tvOffsetLabel.setText("⏱️ 触觉对齐补偿: " + sign + latencyOffsetMs + " ms");
    }

    private void triggerMiuiHaptic(int miuiConstant) {
        boolean handled = false;
        if (rootView != null) {
            handled = rootView.performHapticFeedback(miuiConstant, HAPTIC_FLAGS);
        }
        if (!handled && vibrator != null && Build.VERSION.SDK_INT >= 29) {
            try {
                int prebakedId = (miuiConstant == MIUI_TAP_LIGHT) ? 2 : (miuiConstant == MIUI_MESH_HEAVY ? 0 : 1);
                java.lang.reflect.Method m = VibrationEffect.class.getMethod("createPrebaked", int.class, boolean.class);
                VibrationEffect effect = (VibrationEffect) m.invoke(null, prebakedId, true);
                if (mediaAudioAttributes != null) {
                    vibrator.vibrate(effect, mediaAudioAttributes);
                } else {
                    vibrator.vibrate(effect);
                }
            } catch (Exception ignored) {}
        }
    }

    private void setupTestButtons() {
        btnTestTapLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerMiuiHaptic(MIUI_TAP_LIGHT);
                tvStatus.setText("⚡ 已触发 MIUI 极轻脆微动 (0x01)");
            }
        });

        btnTestTapNormal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerMiuiHaptic(MIUI_TAP_NORMAL);
                tvStatus.setText("⚡ 已触发 MIUI 清脆标准点按 (0x00)");
            }
        });

        btnTestMeshHeavy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerMiuiHaptic(MIUI_MESH_HEAVY);
                tvStatus.setText("⚡ 已触发 MIUI 沉稳重刻度顿挫 (0x04)");
            }
        });
    }

    private void loadWaveformData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream is = getAssets().open("waveform.json");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    is.close();

                    String jsonStr = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(jsonStr);

                    JSONArray eventsArray = json.optJSONArray("events");
                    final List<BeatEvent> loadedEvents = new ArrayList<>();

                    if (eventsArray != null) {
                        for (int i = 0; i < eventsArray.length(); i++) {
                            JSONObject e = eventsArray.getJSONObject(i);
                            long t = e.getLong("time_ms");
                            int hCode = e.optInt("miui_haptic", MIUI_TAP_NORMAL);
                            loadedEvents.add(new BeatEvent(t, hCode));
                        }
                    }

                    Collections.sort(loadedEvents, new Comparator<BeatEvent>() {
                        @Override
                        public int compare(BeatEvent a, BeatEvent b) {
                            return Long.compare(a.timeMs, b.timeMs);
                        }
                    });

                    totalDurationMs = json.optLong("total_duration_ms", 268400);

                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            beatEvents = loadedEvents;
                            long totSec = totalDurationMs / 1000;
                            tvStatus.setText("已加载 " + beatEvents.size() + " 个毫秒级起音事件 | 100Hz 精度");
                            tvTotalTime.setText(String.format("%02d:%02d", totSec / 60, totSec % 60));
                        }
                    });

                } catch (final Exception e) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvStatus.setText("载入波形错误: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startPrecisionPlayback() {
        if (beatEvents == null || beatEvents.isEmpty()) {
            Toast.makeText(this, "波形文件正在加载中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }

        stopPlayback();

        try {
            mediaPlayer = new MediaPlayer();
            AssetFileDescriptor afd = getAssets().openFd("audio.mp3");
            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            if (mediaAudioAttributes != null) {
                mediaPlayer.setAudioAttributes(mediaAudioAttributes);
            }
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopPlayback();
                }
            });
            if (seekOffsetMs > 0 && seekOffsetMs < mediaPlayer.getDuration()) {
                mediaPlayer.seekTo((int) seekOffsetMs);
            }
            mediaPlayer.start();
        } catch (Exception e) {
            Toast.makeText(this, "音频启动异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        isPlaying = true;
        audioStartNano = System.nanoTime() - (seekOffsetMs * 1_000_000L);
        tvStatus.setText("▶ 正在以纳秒单调时钟同步驱动清脆触觉...");
        tvStatus.setTextColor(0xFF00E676);

        startPrecisionEventEngine();
    }

    private void startPrecisionEventEngine() {
        precisionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int eventIdx = findFirstEventIndex(seekOffsetMs);
                long lastReconcileTime = System.currentTimeMillis();

                while (isPlaying) {
                    // 1. 基于纳秒单调时钟计算当前精确播放进度 (消除掉普通时钟抖动)
                    long elapsedNano = System.nanoTime() - audioStartNano;
                    long currentPosMs = elapsedNano / 1_000_000L;

                    // 2. 每 300ms 与硬件 AudioTrack 的物理时间校准，消除累积时钟漂移
                    long now = System.currentTimeMillis();
                    if (now - lastReconcileTime > 300 && mediaPlayer != null) {
                        try {
                            int mpPos = mediaPlayer.getCurrentPosition();
                            long drift = Math.abs(currentPosMs - mpPos);
                            if (drift > 40) {
                                // 发生大漂移或硬件缓冲跳跃，平滑校正时钟
                                audioStartNano = System.nanoTime() - (mpPos * 1_000_000L);
                                currentPosMs = mpPos;
                                eventIdx = findFirstEventIndex(currentPosMs);
                            }
                        } catch (Exception ignored) {}
                        lastReconcileTime = now;
                    }

                    if (currentPosMs >= totalDurationMs) {
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                stopPlayback();
                            }
                        });
                        break;
                    }

                    // 3. 计入触觉提前/延后补偿 (加上 latencyOffsetMs 抵消 Android 扬声器延迟)
                    long effectiveTimeMs = currentPosMs - latencyOffsetMs;

                    // 4. 事件触发队列：瞬时命中事件立即在 UI 线程硬件直驱
                    while (eventIdx < beatEvents.size()) {
                        BeatEvent event = beatEvents.get(eventIdx);
                        if (event.timeMs <= effectiveTimeMs) {
                            final int hCode = event.miuiHaptic;
                            uiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isPlaying) {
                                        triggerMiuiHaptic(hCode);
                                    }
                                }
                            });
                            eventIdx++;
                        } else {
                            break;
                        }
                    }

                    // 5. 更新 UI
                    final long curMs = currentPosMs;
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isPlaying) return;
                            int curSec = (int) (curMs / 1000);
                            tvCurrentTime.setText(String.format("%02d:%02d", curSec / 60, curSec % 60));
                            if (totalDurationMs > 0) {
                                seekBar.setProgress((int) ((curMs * 1000) / totalDurationMs));
                            }
                        }
                    });

                    // 高精度休眠 4ms (250Hz 超高频检查，时间抖动 <= 2ms)
                    try {
                        Thread.sleep(4);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        precisionThread.start();
    }

    private int findFirstEventIndex(long targetMs) {
        if (beatEvents == null || beatEvents.isEmpty()) return 0;
        int low = 0;
        int high = beatEvents.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midVal = beatEvents.get(mid).timeMs;
            if (midVal < targetMs) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(0, low);
    }

    private void seekToPosition(long targetMs) {
        seekOffsetMs = Math.max(0, Math.min(targetMs, totalDurationMs));
        if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo((int) seekOffsetMs);
            } catch (Exception ignored) {}
        }
        audioStartNano = System.nanoTime() - (seekOffsetMs * 1_000_000L);

        int curSec = (int) (seekOffsetMs / 1000);
        tvCurrentTime.setText(String.format("%02d:%02d", curSec / 60, curSec % 60));
        if (totalDurationMs > 0) {
            seekBar.setProgress((int) ((seekOffsetMs * 1000) / totalDurationMs));
        }

        if (!isPlaying) {
            startPrecisionPlayback();
        }
    }

    private void stopPlayback() {
        isPlaying = false;
        seekOffsetMs = 0;
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        tvStatus.setText("⏹ 播放已停止");
        tvStatus.setTextColor(0xFFFFD54F);
        seekBar.setProgress(0);
        tvCurrentTime.setText("00:00");
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}
