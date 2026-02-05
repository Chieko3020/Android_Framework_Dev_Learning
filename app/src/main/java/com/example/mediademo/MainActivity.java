package com.example.mediademo;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ViewModelProvider;
import android.content.ServiceConnection;
import android.content.ComponentName;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
/*
 * app层调用练手demo
 *
 * 如何统一处理来自不同来源的控制指令（通知栏、蓝牙设备、语音命令等）？
 * 如何确保播放状态在各种场景下同步更新？
 * 如何遵循 Android 的最佳实践构建健壮的媒体应用？
 *
 * 使用 AudioRecord API 实现录音功能，子线程录音保存为pcm，主线程再手动封装wave header
 * 录音参数是固定的 44100Hz采样率 单声道 16bit 位深
 * 使用 SAF 调用 DocumentsUI 选择目录授予应用访问权限 播放该目录（但没有递归子目录）的音频
 *   也可以改成原先的 ACTION_OPEN_DOCUMENT 选择单个文件
 * 使用 ViewModel 和 LiveData 来管理和更新界面的播放/录音/暂停按钮
 * 使用 Service 管理录音与播放，避免activity重建（旋转屏幕，点击返回键）导致录音/播放中断
 * 使用 Broadcast Receiver 监听耳机拔出事件，停止录音和播放
 * 使用 AudioManager Audio Focus 管理音频焦点，处理多个应用/来电对音频的抢占
 * TODO : adb 模拟来电中断，处理音频和录音暂停，音频焦点控制
 */


// Activity是应用中负责与用户交互的单一屏幕。几乎所有的 APK 都有至少一个 Activity。它管理着生命周期（onCreate, onDestroy）
// 当启动 Activity 时，底层是由 AMS (Activity Manager Service) 负责调度和管理的
// 注意默认情况下，旋转屏幕会导致 Activity 销毁并重新执行 onCreate() 需要使用 onSaveInstanceState 或 ViewModel 来保存状态

// Service没有界面，专门在后台执行耗时操作。如果使用service来代替录音线程，这样即使用户把 Activity 关了（切换到别的应用）依然可以继续
// Service 同样由 AMS 管理，且常驻后台，不依赖于 UI

// Broadcast Receiver是应用对外部事件（如电量低、插入耳机、网络变化）的，基于Pub-Sub模式的通信机制
// 由 PMS (Package Manager Service) 或系统内核触发。

// Content Provider负责在不同的应用之间共享数据（如读取系统通讯录、相册）, SAF (Storage Access Framework) 背后就涉及到了 ContentProvider。
// 它封装了底层数据库（如 SQLite）的操作，提供统一的接口供其他应用查询。
// 当通过 Intent.ACTION_OPEN_DOCUMENT 选择音频文件时，系统其实是去访问了存储设备的 ContentProvider 来获取文件的 Uri。
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MediaDemo";
    private static final int REQUEST_CODE = 1001;
    
    // 音频参数
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private TextView statusText;
    private Button btnPlay, btnRecord, btnStopRecord;
    private android.widget.ProgressBar volumeBar;
    
    private MediaPlayer mediaPlayer;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private String pcmPath;
    private String wavPath;
    private AudioViewModel viewModel;
    private AudioRecordService audioService;
    private Boolean isBound = false;

    // 监听来自 Service 的 UI 更新广播
    private final BroadcastReceiver uiUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            Log.d(TAG,"UI BROADCASTREVICER IS CALLED");
            if ("com.example.mediademo.UPDATE_UI".equals(intent.getAction())) {
                Log.d(TAG, "收到 UI 更新广播，同步状态");
                if (audioService != null) {
                    viewModel.updateRecordingState(audioService.isRecording());
                    viewModel.isPlaying.setValue(audioService.isPlaying());
                    if (!audioService.isRecording() && !audioService.isPlaying()) {
                        viewModel.statusText.setValue("状态：已停止");
                    }
                }
            }
            else if ("com.example.mediademo.VOLUME_UPDATE".equals(intent.getAction())) {
                int level = intent.getIntExtra("level", 0);
                viewModel.volumeLevel.postValue(level); // 注意：Service 在子线程计算，需用 postValue
            }
        }
    };


    // livecycle start
    // onCreate() Activity 第一次被创建时 只会在创建activity调用一次 必须在这里调用 setContentView 来定义 UI并初始化（绑定按钮事件、初始化变量）
    // onStart() Activity 正在变为可见，但还不能与用户交互，此时 Activity 已经出现在屏幕上，但还没到前台。
    // onResume() Activity 准备好与用户进行交互（获取了用户焦点） 重新切换到前台会再次调用
    // onPause() 系统准备启动或恢复另一个 Activity 时 Activity 依然可见，但失去了焦点
    // onStop() Activity 对用户不再可见时
    // onDestroy() Activity 即将被销毁（用户关闭它，或系统内存不足回收它） 
    // 必须在这里确保所有的资源（MediaPlayer、AudioRecord、线程）都被正确释放，否则会导致内存泄漏

    // todo : 来电中断录音  PhoneStateListener AudioManager.OnAudioFocusChangeListener
    // 旋转屏幕activity重建问题 ViewModel Foreground Service
    // 把 AudioRecord 和 MediaPlayer 的逻辑从 MainActivity 移到 Service
    // 其它应用中断播放 断点续播 Audio Focus AudioService 管理多个应用音频流
    // 返回键中断

    // ViewModel 用于将状态公开给界面，以及封装相关的业务逻辑，可以缓存状态，还能在配置更改后持久保存状态
    // 实例化 ViewModel 时，您会向其传递实现 ViewModelStoreOwner 接口的对象。
    // 它可能是 Navigation 目的地、Navigation 图表、activity、fragment 或实现接口的任何其他类型。
    // 然后，ViewModel 的作用域将限定为 ViewModelStoreOwner 的 Lifecycle。
    // 它会一直保留在内存中，直到其 ViewModelStoreOwner 永久消失。
    // 当 ViewModel 的作用域 fragment 或 activity 被销毁时，异步工作会在作用域限定到该 fragment 或 activity 的 ViewModel 中继续进行。这是持久性的关键。
    // 也就是说对于 activity ViewModel的生命周期是到activity正常onDestroy（而不是重建）并且finished才会调用onCleared结束
    // 通常在系统首次调用 activity 对象的 onCreate() 方法时请求 ViewModel。
    // 系统可能会在 activity 的整个生命周期内多次调用 onCreate()，如在旋转设备屏幕时。
    // ViewModel 存在的时间范围是从您首次请求 ViewModel 直到 activity 完成并销毁。
    // ViewModel 通常不应引用视图、Lifecycle 或可能存储对 activity 上下文的引用的任何类。
    // 由于 ViewModel 的生命周期大于界面的生命周期，因此在 ViewModel 中保留与生命周期相关的 API 可能会导致内存泄漏。

    // 每个activity继承自ComponentActivity都有一个ViewModelStore对象，它实际上是个哈希表，用来保存该activity的所有ViewModel实例
    // 例如 activity在调用onRetainNonConfigurationInstance()方法因配置更改试图重建时
    // ComponentActivity 会把当前的ViewModelStore包装到NonConfigurationInstances静态内部类对象并返回给AMS
    // 然后activity在onCreate重建时会通过调用getLastNonConfigurationInstances()拿回来ViewModelStore
    // 并且取出ViewModel恢复现场

    // 不能将View对象的各种Ui控件的对象引用放在ViewModel管理，因为ViewModel的生命周期大于Activity
    // 如果Activity重建申请了新的View对象而ViewModel还保存原有对象的引用
    // 那么在造成内存泄露的同时 Activity重新获取到的对象引用也是旧的 无法操作新申请的View对象

    //

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            AudioRecordService.AudioBinder binder = (AudioRecordService.AudioBinder) iBinder;
            audioService = binder.getService();
            isBound = true;
            
            // 同步 Service 的真实录音状态到 ViewModel
            boolean recording = audioService.isRecording();
            viewModel.isRecording.setValue(recording);
            if (recording) {
                viewModel.statusText.setValue("状态：正在录制（已恢复）");
            }

            // 同步播放状态
            boolean playing = audioService.isPlaying();
            viewModel.isPlaying.setValue(playing);
            if (playing) {
                viewModel.statusText.setValue("状态：正在播放（已恢复）");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须先调用父类的实现，否则系统会抛出 SuperNotCalledException
        super.onCreate(savedInstanceState);
        // 根据 XML 文件生成 View 对象树
        setContentView(R.layout.activity_main);
        viewModel = new ViewModelProvider(this).get(AudioViewModel.class);
        // 获取几个控件的对象引用 类型不安全
        // 必须在设置了视图之后调用 否则有空指针异常
        // 如果找不到对应控件id会抛出空指针异常 它会检查id
        // 换视图绑定没有空指针风险 为每个 XML 布局文件自动生成一个绑定类
        // 也可以使用 compose 利用组合注解与 setContent
        statusText = findViewById(R.id.statusText);
        btnPlay = findViewById(R.id.btnPlay);
        btnRecord = findViewById(R.id.btnRecord);
        btnStopRecord = findViewById(R.id.btnStopRecord);
        volumeBar = findViewById(R.id.volumeBar);

        // UI控制逻辑写在ViewModel LiveData 闭包、
        // LiveData 节省了大量的防御性代码（判空、生命周期检查、状态恢复） 生命周期自动管理 观察者也会自动销毁
        // UI数据更改（比如由Service引起的录音/播放因不同操作触发的各种状态改变）需要在activity对应代码处手动获取最新值
        // 主线程用 setValue()，子线程用 postValue() 调用后，所有观察者（Observers）会立刻收到更新通知
        viewModel.isRecording.observe(this, recording -> {
            btnRecord.setEnabled(!recording);
            btnStopRecord.setEnabled(recording);
        });
        viewModel.statusText.observe(this, text -> {
            statusText.setText(text);
        });
        viewModel.volumeLevel.observe(this, level -> {
            if (volumeBar != null) {
                volumeBar.setProgress(level);
            }
        });


        Intent intent = new Intent(this, AudioRecordService.class);
        bindService(intent, connection, BIND_AUTO_CREATE);



        // 设置私有目录路径
        pcmPath = getExternalFilesDir(null).getAbsolutePath() + "/record.pcm";
        wavPath = getExternalFilesDir(null).getAbsolutePath() + "/record.wav";

        checkPermissions();
        // 注册 UI 更新广播
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.mediademo.UPDATE_UI");
        filter.addAction("com.example.mediademo.VOLUME_UPDATE");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(uiUpdateReceiver, filter);
        }

        // 绑定点击事件的回调函数
        btnPlay.setOnClickListener(v -> playAudio());
        // btnRecord.setOnClickListener(v -> startRecording());
        // btnStopRecord.setOnClickListener(v -> stopRecording());
        btnRecord.setOnClickListener(v -> startRecordingByService());
        btnStopRecord.setOnClickListener(v -> stopRecordingByService());


    }

    private void startRecordingByService() {
        if(isBound) {
            // 先启动服务，确保它独立于 Activity 生命周期
            Intent intent = new Intent(this, AudioRecordService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,CHANNEL_CONFIG,AUDIO_FORMAT);
            audioService.startRecording(SAMPLE_RATE, CHANNEL_CONFIG,AUDIO_FORMAT, bufferSize);
            viewModel.updateRecordingState(true);
        } else {
            Log.e(TAG, "服务未绑定，无法录音");
        }
    }

    private void stopRecordingByService() {
        if (isBound) {
            audioService.stopRecording();
            viewModel.updateRecordingState(false);
            pcmToWav(pcmPath, wavPath);
            // 状态更新已经由 viewModel.updateRecordingState 处理，这里可以补充具体路径信息
            viewModel.statusText.setValue("状态：录音完成\n已保存至: " + wavPath);
            showToast("录音已保存");
        }
    }



    // 检查应用是否获得了用户授予的必要权限：录音，读写文件
    // 由于 Android 11+ 使用了分区存储来限制应用的访问权限
    // 这意味着应用无法直接通过 File 对象访问外部存储的公共目录，除非该文件是由应用创建的
    // 除了在xml写权限 还需要动态申请运行时权限
    // 对于Android 10以下，旧的 WRITE_EXTERNAL_STORAGE、READ_EXTERNAL_STORAGE不提供任何其他权限
    // Android 11+ 如果要访问公共区域下自己创建文件外的其他文件需要单独申请 MANAGE_EXTERNAL_STORAGE
    // 使用Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION让用户去权限配置页自己配置
    // 可以授权所有文件读写权限和MediaStore.Files表内容访问权限
    // 申请RECORD_AUDIO 和 READ_MEDIA_AUDIO
    // Android 13+ 将之前的权限细分为Image/Audio/Video三种媒体文件类型对应的读写权限
    // 写媒体文件时(Image/Video/Audio)，要用MediaStore API的方式
    // 或者使用 SAF 框架让用户选择一些文件授予应用访问权限
    // 也可以改成ACTION_OPEN_DOCUMENT_TREE 选择目录 然后获取目录下的所有文件的权限
    // 这时要用 DocumentFile 类在授权的目录下遍历所有的 .mp3 文件并做一个列表
    private void checkPermissions() {
        List<String> permissionList = new ArrayList<>();
        // 检查用户是否已向您的应用授予特定权限，请将该权限传入 ContextCompat.checkSelfPermission() 方法。
        // 根据您的应用是否具有相应权限，此方法会返回 PERMISSION_GRANTED 或 PERMISSION_DENIED

        // 录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.RECORD_AUDIO);
        }

        // 存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // Android 12-
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            // Android 10-
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
        }

        if (!permissionList.isEmpty()) {
            //  Activity 会通过 Binder 发送请求给系统服务 PermissionManagerService，由它弹出系统级的对话框
            ActivityCompat.requestPermissions(this, permissionList.toArray(new String[0]), REQUEST_CODE);
        }
    }

    // 现在的外部存储通常就是存储空间而不是sdcard

    // 内部存储一般指 系统根目录 /data/user/0/package_name 只有应用能访问
    // /data 需要root权限才能访问
    // 外部存储一般指 存储空间 /storage/emulated/0/ 在文件管理器里看到的根目录 这不是真正的根目录
    // 应用也可以选择外部存储 /storage/emulated/0/Android/data/package_name/files/

    // 应用私有目录只有应用能够访问 可以是内部存储 也可以是外部存储 只要是该应用创建的
    // 对于应用在/storage/emulated/0/Android/data/package_name/files/
    // 在 Android 11+ 由于分区存储利用了 FUSE (Filesystem in Userspace) 
    // 当尝试访问 Android/data 时，系统底层的存储服务会进行拦截 返回一个空列表
    // 系统在Volume Daemon挂载存储设备时，通过 Mount 命令的参数来限制不同用户对这些文件夹的访问权限
    // 每个应用在 Android 里其实都是一个独立的用户 ID，即 UID

    // 公共目录一般是外部存储的根目录及其子目录（如 Download/, Music/, DCIM/）
    // 访问公共目录需要获取用户权限 或者使用SAF向用户申请uri

    // 对于权限管理 在不同版本有不同管理方法
    // Android 9- xml静态权限
    // Android 10- 动态权限 也就是运行时权限 对于存储依然使用 READ_EXTERNAL_STORAGE 和 WRITE_EXTERNAL_STORAGE
    // Android 11+ 引入了 分区存储（Scoped Storage）应用访问私有目录不需要权限 访问公共目录需要权限
    // Android 13+ 引入了 媒体权限（Media Permissions）应用访问媒体文件需要Image/Audio/Video三种媒体文件类型对应的读写权限

    // 申请权限
    // 在xml静态声明 在运行时动态申请 使用Intent跳转系统设置让用户配置
    // SAF 应用申请访问权限时 ContentProvider 调用系统的文档提供程序让用户选择文件然后会返回这个文件的uri 
    // 具体来说系统底层会启动 DocumentsUI 应用 
    // 这个应用拥有 root 权限，它可以跨越所有应用的限制去读取文件，然后通过 ContentProvider 将文件描述符跨进程传递给应用
    //  MANAGE_EXTERNAL_STORAGE是一个权限 允许应用访问外部存储的所有文件
    // Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION 是一个 Intent Action 除了/data和应用私有外均可访问 由AppOps管理
    // 当发送这个 Intent 时，系统会直接跳转到一个专门的设置页面
    // 列表里显示了所有申请了ALL_FILES_ACCESS_PERMISSION权限的应用，用户可以在这里手动点击开关来授权

    // 播放功能
    private void playAudio() {
        // 使用 Intent 调用系统文件选择器访问外部路径
        // SAF Storage Access FrameWork
        // 本质是返回uri而不是文件路径
        // 1. 启用系统的文档提供程序 DoucumentsProvider子类
        // 2. 客户端调用intnet操作（例如ACTION_OPEN_DOCUMENT） 接收返回的文档
        // 如果您想让应用读取或导入数据，请使用 ACTION_GET_CONTENT。
        // 使用此方法时，应用会导入数据（如图片文件）的副本。
        // 如果您想让应用获得对文档提供程序所拥有文档的长期、持续访问权限，
        // 请使用 ACTION_OPEN_DOCUMENT。
        // 例如，照片编辑应用可让用户编辑存储在文档提供程序中的图片

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, 2001);


//        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); //启动 DocumentsUI 应用
//        intent.addCategory(Intent.CATEGORY_OPENABLE); // fliter 只显示那些可以被打开的文件
//        intent.setType("audio/*"); // MIME fliter 只显示音频文件 通过扫描文件的后缀名或者文件头，在 MediaStore 数据库中匹配 MIME 类型来实现过滤的
//        startActivityForResult(intent, 2001);// 启动文件选择界面，并等待它返回结果。
        // 2001 是自定义请求码 用于在onActivityResult中区分不同的请求
        // 弃用 更改为Activity Result API
        // 使用 Activity Result API 替代 startActivityForResult 避免activity销毁时结果丢失 
        // 也不需要根据请求码来switch 而是通过一个注册好的回调对象来直接处理
        // 定义一个ActivityResultLauncher 在onCreate注册
        // 调用方式从 startActivityForResult(intent, 2001) 变为 launcher.launch(intent)
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2001 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                DocumentFile dir = DocumentFile.fromTreeUri(this, uri);
                List<Uri> urilist = new ArrayList<>();
                for (DocumentFile file : dir.listFiles()) {
                    if (file.isFile() && file.getType().startsWith("audio/")) {
                        urilist.add(file.getUri());
                    }
                }
                if (isBound && !urilist.isEmpty()) {
                    Intent intent = new Intent(this, AudioRecordService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    audioService.setPlaylist(urilist);
                    viewModel.isPlaying.setValue(true);
                    viewModel.statusText.setValue("状态：正在播放选择的文件");
                } else {
                    showToast("服务未绑定，无法播放");
                }
                // playUriAudio(uri);
            }
        }
    }


    // mediaPlayer 是 Android 系统提供的一个高层级多媒体播放控制器 专门用于播放音频和视频
    // 接受路径或uri 自动解码/缓冲/输出到声音输出设备
    // 实际上它是个代理类 它通过binder通信控制audioserver进程的nativeC++ Stagefright/NuPlayer
    // mediaPlayer 会占用硬件解码器和内存资源 使用前后应该检查
    // setDataSource 设置数据源  uri则会通过 ContentResolver 去找对应的文件流
    // prepare 准备播放 播放器会尝试解析文件头、检查编码格式、初始化底层解码器 如果播放网络资源会阻塞主线程 应该换prepareAsync
    // start 开始播放 把解码后的音频流推送到 AudioFlinger 进行混音并输出
    // 还可以使用 ExoPlayer
    // 另外AudioTrack 是和AudioRecord类似的更底层的 API。它不负责解码，只负责把原始的 PCM 数据推送到硬件
    private void playUriAudio(Uri uri) {
        if (isBound) {
            // 启动服务以保证后台播放
            Intent intent = new Intent(this, AudioRecordService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            audioService.playAudio(uri);
            viewModel.isPlaying.setValue(true);
            viewModel.statusText.setValue("状态：正在播放选择的文件");
        } else {
            showToast("服务未绑定，无法播放");
        }
    }



    // MediaRecorder是高层级 API，集成了编码器和封装器。
    // 但这样就没法保存 pcm/wav了
    // MediaRecorder recorder = new MediaRecorder();
    // recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    // recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); // 封装格式
    // recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);    // 编码格式
    // recorder.setOutputFile(path);
    // recorder.prepare();
    // recorder.start(); // 自动开始录制并保存到文件
    // 实际上不论是MediaRecorder还是AudioRecord 都是通过 AudioFlinger 进行音频的采集和播放
    // 请求通过 Binder 传给 audioserver 进程 系统会检查权限，并决定从哪个硬件（麦克风）获取数据
    // 对于AudioRecord，它会直接把硬件采集到的 PCM 数据通过共享内存传给应用
    // 对于MediaRecorder，它会在系统服务层调用 MediaCodec 进行压缩（比如压成 AAC），然后再写成文件


    // 录音功能
    private void startRecording() {
        // 返回成功创建AudioRecord对象所需的最小缓冲区大小（以字节为单位）
        // 不存在溢出问题 因为缓冲区是循环覆盖的

        // MediaRecorder 是高层 API，直接出 mp4/aac；而 AudioRecord 是底层 API，输出的是原始的 PCM (脉冲编码调制) 数据
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("checkSelfPermission Failed");
            return;
        }
        // AudioRecord类管理Java应用程序的音频资源，以便从平台的音频输入硬件录制音频
        // 一旦创建，一个AudioRecord对象初始化其关联的音频缓冲区
        // 上面使用getMinBufferSize返回成功创建AudioRecord对象所需的最小缓冲区大小（以字节为单位）
        // AudioRecord(int audioSource, 指定音频源
        //              int sampleRateInHz, 采样率
        //              int channelConfig, 声道
        //              int audioFormat, 格式
        //              int bufferSizeInBytes)
        // MediaRecorder.AudioSource.MIC 从麦克风获取声音 
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        // 交互调整 开始录音不能再次点击启动
        isRecording = true;
        btnRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        statusText.setText("状态：正在录制 PCM...");

        audioRecord.startRecording();

        // 开启线程写入文件流
        // 如果使用主线程来进行录音操作 会阻塞 MainActivity 的用户界面UI交互 ANR
        // 需要先录完pcm再封装wav 因为你不知道最终录多久 也就不知道pcm长度 无法定义wav header
        new Thread(() -> {
            try (FileOutputStream os = new FileOutputStream(pcmPath)) {
                byte[] data = new byte[bufferSize];
                while (isRecording) {
                    int read = audioRecord.read(data, 0, bufferSize); // 阻塞调用 它会一直等到硬件缓冲区有数据了才返回
                    if (read > 0) {
                        os.write(data, 0, read); // todo : 空间不足检查 StatFS 
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "录音写入失败", e);
            }
        }).start();
    }

    private void stopRecording() {
        isRecording = false;
        // 停止录音 释放资源
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        // 交互恢复
        btnRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);
        
        // PCM 2 WAV
        // AudioRecord 录出来的 .pcm 文件是原始数据，没有采样率、声道数等信息无法直接播放
        // .wav 文件其实就是 PCM 数据 + 44 字节的 Header
        pcmToWav(pcmPath, wavPath);
        statusText.setText("状态：录音完成\n已保存至: " + wavPath);
        showToast("录音已保存");
        // todo : MediaStore 注入：通过 ContentValues 将录好的 WAV 文件“插入”到系统的 Music 库中，让系统自带的音乐播放器也能搜到它
    }

    private void pcmToWav(String pcmPath, String wavPath) {
        FileInputStream in = null; // 读取的原始文件
        FileOutputStream out = null; // 写入的最终文件
        long totalAudioLen = 0; // PCM音频数据长度
        long totalDataLen = totalAudioLen + 36; // 整个 WAV 文件除去开头 8 个字节（"RIFF" 和文件长度字段本身）后的长度 
        long longSampleRate = SAMPLE_RATE; // 采样率
        int channels = 1; // 声道数
        long byteRate = 16 * SAMPLE_RATE * channels / 8; // 字节率
        byte[] data = new byte[1024]; // 缓冲区
        try {
            in = new FileInputStream(pcmPath);
            out = new FileOutputStream(wavPath);
            totalAudioLen = in.getChannel().size();
            totalDataLen = totalAudioLen + 36;

            // WAV header
            writeWavHeader(out, totalAudioLen, totalDataLen, longSampleRate, channels, byteRate);
            
            while (in.read(data) != -1) {
                out.write(data);
            }
            in.close();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 44 Bytes WAV Header
    // 其实 AAC 的ADTS和H.264的NALU和这个差不多

    // wave riff 协议
    // WAV 文件本质上是一个 RIFF (Resource Interchange File Format) 格式的文件。它由多个块（Chunk）组成
    // 4("RIFF"标志) + 
    // 4(小端字节序文件长度) + 
    // 4(文件类型"WAVE") + 
    // 4("fmt "标志) + 
    // 4(fmt 块长度) + 
    // 2(音频格式PCM) + 
    // 2(声道数) + 
    // 4(采样率) +
    // 4(字节率) + 
    // 2(块对齐) + 
    // 2(位深度) + 
    // 4("data"标志) +
    // 4(数据长度)
    //一共44字节
    private void writeWavHeader(FileOutputStream out, long totalAudioLen, long totalDataLen, long sampleRate, int channels, long byteRate) throws IOException {
        byte[] header = new byte[44];
        header[0] = 'R'; // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';

        // 每次取低8位 取一次右移8位再取下一次 最终拆成4个字节
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);

        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';

        header[12] = 'f'; // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;

        header[20] = 1; // format = 1 (PCM)
        header[21] = 0;

        header[22] = (byte) channels;
        header[23] = 0;

        // 字节拆分
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);

        // 字节拆分
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);

        header[32] = (byte) (channels * 16 / 8); // block align
        header[33] = 0;
        header[34] = 16; // bits per sample
        header[35] = 0;

        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';

        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        // 写入header到文件流给最终文件
        out.write(header, 0, 44);
    }

    private void showToast(String message) {
        // toast 组件的即时消息弹窗 实际上它也是binder IPC
        // 当调用 Toast.show() 时，应用会通过 Binder 向系统服务 NotificationManagerService (NMS) 发送一个请求
        // NMS 接收到请求后，会协调 WindowManagerService (WMS) 在屏幕的最顶层创建一个临时的系统窗口来显示这段文字
        // 也就是说这个系统级别的控件保证 即便app关了 这个弹窗也会显示直到Toast.LENGTH_LONG计时结束
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销 UI 更新广播
        unregisterReceiver(uiUpdateReceiver);
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}
