package com.example.mediademo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.Manifest;

import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

public class AudioRecordService extends Service {
    private static final String TAG = "AudioRecordService";
    private static final String CHANNEL_ID = "AudioRecordChannel";
    private AudioRecord audioRecord;
    private MediaPlayer mediaPlayer;
    private boolean isRecording = false;
    private String pcmPath;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private final Object focusLock = new Object();
    private boolean resumeOnFocusGain = false;

    private List<Uri> playlist = new ArrayList<>();
    private int currentIndex = -1;

    // 广播接收器：监听耳机拔出
    // adb shell am broadcast -a com.example.mediademo.TEST_NOISY -p com.example.mediademo --receiver-include-background
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, ">>> 收到广播: " + action); // 必须看到的 Log

            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action) || 
                "com.example.mediademo.TEST_NOISY".equals(action)) {
                
                Log.d(TAG, ">>> 正在执行停止逻辑...");
                stopRecording();
                stopPlayback();

                // 发送本地广播通知 Activity 更新 UI
                Intent updateIntent = new Intent("com.example.mediademo.UPDATE_UI");
                sendBroadcast(updateIntent);
            }
        }
    };

    // 音频焦点监听器
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        Log.d(TAG, "FOCUSCHANGELISTENER IS CALLED: " + focusChange);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "AUDIOFOCUS_GAIN");
                // 重新获得焦点
                if (resumeOnFocusGain) {
                    synchronized (focusLock) {
                        resumeOnFocusGain = false;
                        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                            mediaPlayer.setVolume(1.0f, 1.0f);
                            mediaPlayer.start();
                            notifyUiUpdate();
                        }
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d(TAG, "AUDIOFOCUS_LOSS");
                // 永久失去焦点
                stopPlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 暂时失去焦点（如来电）
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT");
                synchronized (focusLock) {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        resumeOnFocusGain = true;
                        mediaPlayer.pause();
                    }
                    if (isRecording) {
                        stopRecording();
                    }
                }
                notifyUiUpdate();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                // 暂时失去焦点，但可以降低音量播放（如导航播报）
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.setVolume(0.2f, 0.2f);
                    notifyUiUpdate();
                }
                break;
        }
    };

    private void notifyUiUpdate() {
        Intent updateIntent = new Intent("com.example.mediademo.UPDATE_UI");
        updateIntent.setPackage(getPackageName());
        sendBroadcast(updateIntent);
    }

    // Binder 给 Activity 提供调用接口
    // 这样 Activity能够通过获得Service对象来getter录音状态
    public class AudioBinder extends Binder {
        AudioRecordService getService() {
            return AudioRecordService.this;
        }
    }

    private final IBinder binder = new AudioBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel(); // 通知栏显示录音
        pcmPath = getExternalFilesDir(null).getAbsolutePath() + "/record.pcm";
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initAudioFocusRequest();

        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction("com.example.mediademo.TEST_NOISY");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // TIRAMISU (Android 13) 及以上版本支持显式指定导出标志
            // 因为我们需要通过 ADB 触发，所以必须设置为 RECEIVER_EXPORTED
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            // 旧版本保持原样
            registerReceiver(noisyReceiver, filter);
            Log.d(TAG, ">>> 广播接收器已注册");
        }


        // 在 MainActivity.java 的某个点击事件或 onCreate 中
//        new Handler(Looper.getMainLooper()).postDelayed(() -> {
//            Log.d("Test", ">>> 正在发送应用内测试广播...");
//            Intent intent = new Intent("com.example.mediademo.TEST_NOISY");
//            intent.setPackage(getPackageName()); // 明确指定包名
//            sendBroadcast(intent);
//        }, 5000); // 启动 5 秒后自动发广播
    }

    private void initAudioFocusRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
        }
    }

    public void startRecording(int sampleRate, int channelConfig, int audioFormat, int bufferSize) {
        if (isRecording) return;

        int res;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            res = audioManager.requestAudioFocus(focusRequest);
        } else {
            res = audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
        }

        if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "无法获取音频焦点，录音取消");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            stopSelf();
            return;
        }

        // 提升为前台服务，防止旋转或切后台被杀
        startForeground(1, getNotification("正在录音..."));

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize);
        
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            isRecording = false;
            stopForeground(true);
            return;
        }

        isRecording = true;
        audioRecord.startRecording();

        new Thread(() -> {
            try (FileOutputStream os = new FileOutputStream(pcmPath)) {
                byte[] data = new byte[bufferSize];
                while (isRecording) {
                    int read = audioRecord.read(data, 0, bufferSize);
                    if (read < 0) {
                        Log.e(TAG, "读取音频数据失败，错误码: " + read);
                        isRecording = false;
                        break;
                    }
                    if (read > 0) os.write(data, 0, read);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                // 3. 录音异常结束，通知 UI
                stopRecording();
                Intent updateIntent = new Intent("com.example.mediademo.UPDATE_UI");
                updateIntent.setPackage(getPackageName());
                sendBroadcast(updateIntent);
            }
        }).start();
    }

    public void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audioRecord", e);
            }
            audioRecord.release();
            audioRecord = null;
        }
        stopForeground(true);
        if (!isPlaying()) {
            stopSelf();
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void setPlaylist (List<Uri> uri) {
        this.playlist = uri;
        if(!playlist.isEmpty())
            playTrack(0);
    }

    private void playTrack (int index) {
        if(index < 0 || index > playlist.size())
            return;
        else {
            this.currentIndex = index;
            Uri uri = playlist.get(index);
            playAudio(uri);
        }
    }

    public void playnext() {
        if (playlist != null && currentIndex < playlist.size() - 1) {
            playTrack(currentIndex+1);
        }
        else {
            abandonFocus();
            stopForeground(true);
            if (!isRecording) {
                stopSelf();
            }
        }
    }


    // 播放相关方法
    public void playAudio(Uri uri) {
        int res;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            res = audioManager.requestAudioFocus(focusRequest);
        } else {
            res = audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "无法获取音频焦点，播放取消");
            return;
        }

        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                startForeground(1, getNotification("正在播放音频..."));
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                playnext();
            });
        } catch (IOException e) {
            Log.e(TAG, "播放失败", e);
            abandonFocus();
        }
    }

    public void stopPlayback() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            // 释放焦点
            abandonFocus();
            stopForeground(true);
            Intent updateIntent = new Intent("com.example.mediademo.UPDATE_UI");
            updateIntent.setPackage(getPackageName());
            sendBroadcast(updateIntent);
        }
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 注销广播接收器
        if (noisyReceiver != null) {
            unregisterReceiver(noisyReceiver);
        }
        if (audioRecord != null) {
            audioRecord.release();
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "录音服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification getNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MediaDemo")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
    }
}