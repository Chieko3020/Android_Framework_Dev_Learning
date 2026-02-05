---
date: 2026-02-04

---

# Framework Architecture

- aosp latest-release : android-16.0.0 for api 36

## app

- 负责与用户进行直接的交互，调用下层api来实现业务功能

## framework

- 封装系统功能，对上层提供api和服务
- native c++ 提供硬件交互
- service 提供核心服务，典型例子是activity管理器AMS，窗口管理器WMS, 包管理器PMS, 通过Binder IPC供上层调用
- Java API, 包含组件，View,资源管理，通知等api
- 系统应用，包含Setting,Launcher等

## runtime

- 系统运行时库，包含C++库与ART虚拟机，native服务

## hal

- 标准化的硬件接口，将下层硬件统一对上层提供相同的访问方式

## kernal

- Linux 内核与驱动，典型驱动是Binder IPC

# 组件

## Activity

### 概念

- Activity 类是 Android 组件的重要组成部分，它是包含用户界面的单一屏幕， 是应用与用户交互的唯一入口点
- 每一个 Activity 都必须在 AndroidManifest.xml 中声明
- 主要用于向用户提供UI界面交互
- 当启动 Activity 时，底层是由 AMS (Activity Manager Service) 负责调度和管理的

### 生命周期

- 1. onCreate() : 必须在activity类中重写该方法，用于创建并初始化一个avtivity，并且该方法内部还需要调用setContentView()来为该activity绑定一个界面
    - activity除非重建，该方法只会在初始化调用一次  
- 2. onStart() : 在onCreate()之后调用，此时应用准备进入前台，用户能够看到activity，但是还不能进行交互
- 3. onResume() ：在onStart()之后调用，此时activity能够与用户交互
    - 当用户将应用从后台切换到前台时也会调用该方法
- 4. onPause() : 当用户将应用切换后台，activity进入暂停状态，此时仍然可以更新activity，但是activity对用户将是部分可见的
    - onPause()之后可以是 onResume() 或者 onDestroy() 
    - 如果要保存某一个时刻activity的状态并在之后恢复，不应该使用onPause(),因为它的执行时间非常短，一个方案是使用ViewModel
- 5. onStop() : 当activity对用户不再可见时将调用该方法，比如点击返回键activity被销毁
    - onStop()之后可以是onDestroy()或者onRestart()
- 6. onDestroy() : 销毁activity，必须在该方法内正确回收申请的各种资源

### 保存与恢复

- 当配置改变（屏幕旋转，切换语言，切换深色模式），在默认情况下activity会销毁重建
- 一种方法是使用onSaveInstanceState()保存数据
- 还可以使用ViewModel来保存，对于activity，ViewModel的生命周期是创建到activity结束之后才会调用onCleared()销毁
    - 由于 ViewModel 的生命周期大于activity界面的生命周期，在 ViewModel 中保留与生命周期相关的 API 可能会导致内存泄漏

## Service

### 概念

- Service 是一种可在后台执行长时间运行的操作的应用组件。它不提供界面。服务启动后，即使用户切换到其他应用，也可能会继续运行一段时间。此外，组件可以绑定到服务以与之交互，甚至执行进程间通信 (IPC)。例如，服务可以在后台处理网络事务、播放音乐、执行文件 I/O 或与 content provider 交互
- 服务会在其托管进程的主线程中运行；除非另行指定，否则服务不会创建自己的线程，也不会在单独的进程中运行。应在服务内的单独线程上运行任何阻塞操作，以避免应用无响应 (ANR) 错误
- 如果必须在主线程之外执行工作，但仅在用户与应用互动时执行，则应改为在其他应用组件上下文中创建新线程
- Service 同样由 AMS 管理，且常驻后台，不依赖于 UI

### Forend

- 前台服务会执行一些对用户明显可见的操作，即使用户未与应用互动，前台服务也会继续运行。使用前台服务必须显示通知，以便用户主动了解该服务正在运行，除非停止运行服务或将其从前台移除，否则无法关闭此通知

### Backend

- 后台服务执行的操作不会直接被用户注意到, 例如，如果应用使用服务来压缩其存储空间，该服务通常是后台服务。

### Bound

- 当应用组件通过调用 bindService() 绑定到服务时，该服务处于绑定状态。绑定服务提供客户端-服务器接口，让组件能够与服务交互、发送请求、接收结果，甚至通过进程间通信 (IPC) 跨进程执行这些操作。绑定服务仅在其他应用组件绑定到它时运行。多个组件可以同时绑定到服务，但当所有这些组件解除绑定时，服务会被销毁。
- 服务可以同时采用这两种方式，即可以启动（无限期运行），也可以允许绑定。只需实现两个回调方法即可：onStartCommand() 用于允许组件启动它，onBind() 用于允许绑定。

### 方法

- 1. onStartCommand() 当其他组件（例如 activity）请求启动服务时，系统会通过调用 startService() 来调用此方法。执行此方法时，系统会启动服务，并且该服务可以在后台无限期运行。如果实现了此功能，则需要在服务完成工作后通过调用 stopSelf() 或 stopService() 来停止服务。如果只想提供绑定，则无需实现此方法。
- 2. onBind() 当其他组件想要与服务绑定（例如执行 RPC）时，系统会通过调用 bindService() 来调用此方法。在实现此方法时必须提供一个接口，以便客户端通过返回 IBinder 与服务进行通信。必须始终实现此方法；如果不想允许绑定，则应返回 null。
- 3. onCreate() 系统会在服务首次创建时（在调用 onStartCommand() 或 onBind() 之前）调用此方法来执行一次性设置过程。如果服务已在运行，系统不会调用此方法。
- 4. onDestroy() 当服务不再使用且被销毁时，系统会调用此方法。服务应实现此方法以清理线程、已注册的监听器或接收器等任何资源。这是服务接收的最后一个调用。
- 5. 如果某个组件通过调用 startService()（这会导致调用 onStartCommand()）启动服务，则该服务会继续运行，直到其通过 stopSelf() 自行停止，或其他组件通过调用 stopService() 停止它。
- 6. 如果组件调用 bindService() 来创建服务，并且未调用 onStartCommand()，则只有在组件绑定到该服务时，该服务才会运行。当服务与所有客户端之间的绑定全部取消后，系统会销毁该服务。

## Broadcast Receiver 

### 概念

- 负责监听和响应系统或应用级消息的组件。Android 应用与 Android 系统和其他 Android 应用之间可以相互收发广播消息，这与发布-订阅设计模式相似。系统和应用通常会在发生特定事件时发送广播。举例来说，Android 系统会在发生各种系统事件时发送广播，例如系统启动或设备充电时。再比如，应用可以发送自定义广播来通知其他应用它们可能感兴趣的事件（例如，有新数据下载）
- Broadcast Receiver由 PMS (Package Manager Service) 或系统内核触发。
- 系统会优化广播的传送方式，以保持系统运行状况处于最佳状态。因此无法保证广播的传送时间。需要低延迟进程间通信的应用应考虑使用绑定服务。
- 系统会在发生各种系统事件时自动发送广播，例如当系统进入和退出飞行模式时。所有已订阅的应用都会收到这些广播。
- 可以使用 `adb shell am broadcast` 命令来向应用发送广播来进行测试

### 接收

- 静态注册，在 AndroidManifest.xml 中使用 `<receiver>` 标签声明。系统会在广播发出后启动您的应用。如果应用尚未运行，系统会启动该应用。系统软件包管理器会在应用安装时注册接收器。然后，该接收器会成为应用的一个独立入口点，这意味着如果应用未运行，系统可以启动应用并发送广播。
    - 如果多个应用都使用该方法注册了相同的接收器，那么广播到来时会同时启动大量应用从而对设备性能和用户体验造成严重影响
- 动态注册，在代码中调用 registerReceiver()。通常在 Activity 的 onStart() 注册，在 onStop() 注销。只有应用在运行（或可见）时才能收到。只要注册上下文有效，上下文注册的接收器就会接收广播。这通常是在对 registerReceiver 和 unregisterReceiver 的调用之间。当系统销毁相应上下文时，注册上下文也会失效。例如，如果在 Activity 上下文中注册，只要 activity 保持活跃状态就会收到广播。如果在应用上下文中注册，只要应用在运行就会收到广播
    - 不要在 onReceive() 里做耗时操作，onReceive() 运行在主线程。如果执行超过 10 秒，系统会抛出 ANR。如果需要处理耗时逻辑应该在收到广播后启动一个 Service。
    - 动态注册的广播必须在 onDestroy() 或 onStop() 中调用 unregisterReceiver()，否则会导致 Activity 无法回收。
- 如果在注册广播接收器时指定了权限参数（使用 registerReceiver(BroadcastReceiver, IntentFilter, String, Handler) 或在xml中的 `<receiver>` 标记中指定），则只有通过其xml中的 `<uses-permission>` 标记请求了权限的广播方才能向接收器发送 intent。如果权限危险，还必须向广播方授予该权限。

### 发送

- sendOrderedBroadcast(Intent, String) 方法一次向一个接收器发送广播。当接收器逐个顺序执行时，接收器可以向下传递结果。也可以完全中止广播，使其不会传递给其他接收器。可以控制接收器在同一应用进程中的运行顺序。为此，请使用匹配 intent-filter 的 android:priority 属性。具有相同优先级的接收器将按随机顺序运行。
- sendBroadcast(Intent) 方法会按随机的顺序向所有接收器发送广播。这称为常规广播。这种方法效率更高，但也意味着接收器无法从其他接收器读取结果，无法传递从广播中收到的数据，也无法中止广播。
- 可以指定权限参数。接收器若要接收此广播，则必须通过xml中的 `<uses-permission>` 标记请求该权限。如果权限属于危险权限，必须先授予权限，接收器才能接收广播

## ContentProvider

### 概念

- content provider 可以帮助应用管理对自身存储或由其他应用存储的数据的访问，并提供与其他应用共享数据的方法。它们封装数据，并提供用于定义数据安全性的机制。content provider 是将一个进程中的数据与另一个进程中运行的代码连接的标准接口。封装了底层数据库的操作，提供统一的接口供其他应用查询
- content provider 可精细控制数据访问权限。可以选择仅允许访问自己应用中的内容提供程序、授予访问其他应用的数据的一揽子权限，或配置读取和写入数据的不同权限。
- 可以用notifyChange()在数据发生变化时通知正在监听的组件自动刷新 UI
- 如果自己定义了 ContentProvider，一定要在 Manifest 中设置 `android:exported="false"`，除非需要共享数据
- ContentResolver 的操作（尤其是查询大量媒体文件）可能非常耗时。不要在 Activity 的主线程执行查询，应该使用 CursorLoader 或在子线程中处理

### URI

- 内容 URI 用来在提供程序中标识数据。内容 URI 包括整个提供程序的符号名称（其授权）和指向表的名称（路径）。ContentProvider 使用内容 URI 的路径部分选择需访问的表。提供程序通常会为其公开的每个表显示一条路径。

### 实现

- ContentProvider 实例管理访问权限，通过处理来自其他应用的请求，生成结构化的数据集。所有形式最终会调用 ContentResolver，后者会调用具体的 ContentProvider 方法获取访问权限

- query()：从提供程序检索数据。使用参数选择要排除的表，要返回的行和列以及结果的排序顺序。将数据作为 Cursor 对象返回。
- insert()：向提供程序插入新行。使用参数选择以及获取要使用的列值。返回新插入的行。
- update()：更新提供程序中的现有行。使用参数选择表和行 更新并获取更新后的列值。返回已更新的行数。
- delete()：从提供程序中删除行。使用参数选择表和行删除。返回已删除的行数。
- getType()：返回与内容 URI 对应的 MIME 类型。
- onCreate()：初始化提供程序。在 ContentResolver 对象尝试访问ContentProvider之前，Android 系统会立即调用此方法创建
    - 避免在 onCreate() 中执行冗长的操作。将初始化任务推迟到实际需要时执行。
- 所有这些方法（onCreate() 除外） 可以由多个线程同时调用，因此它们必须是线程安全的。

# 存储

## 内部存储

- /data/user/0/package_name 只有应用能访问
    - /data 需要root权限才能访问

## 外部存储

- /storage/emulated/0/ 而不是 `/` 根目录
    - 应用也可以选择外部存储 /storage/emulated/0/Android/data/package_name/files/
    - /sdcard/ 是 /storage/emulated/0/ 的一个软链接

## 私有目录

- /storage/emulated/0/Android/data/package_name/files/
    - 由于 FUSE (Filesystem in Userspace) 限制，这意味着应用无法直接通过 File 对象访问外部存储的公共目录，除非该文件是由应用创建的。当尝试访问 Android/data 时，系统底层的存储服务会进行拦截返回一个空列表
    - 系统在Volume Daemon挂载存储设备时，通过 Mount 命令的参数来限制不同用户对这些文件夹的访问权限。每个应用在 Android 里其实都是一个独立的用户 ID，即 UID
- /data/user/0/package_name 应用卸载时，数据会被彻底删除。

## 公共目录

- /storage/emulated/0/Android/data 以外
    - 访问公共目录需要获取用户权限 或者使用SAF向用户申请uri


## 访问权限

- Android 9-在xml申请静态权限
- Android 10-需要申请动态权限，也就是运行时权限，对于存储依然使用 READ_EXTERNAL_STORAGE 和 WRITE_EXTERNAL_STORAGE
- Android 11+引入了分区存储（Scoped Storage），应用访问私有目录不需要权限，访问公共目录需要权限
- Android 13+引入了媒体权限（Media Permissions），应用访问媒体文件需要Image/Audio/Video三种媒体文件类型对应的读写权限，需要使用 MediaStore API

## 申请权限

- 可以在xml静态声明，可以在运行时动态申请，也能使用Intent跳转系统设置让用户配置

## SAF

- 应用申请访问权限时 ContentProvider 调用系统的文档提供程序让用户选择文件然后会返回这个文件的uri 
    - 例如当通过 Intent.ACTION_OPEN_DOCUMENT 选择文件时，系统其实是去访问了存储设备的 ContentProvider 来获取文件的 Uri。
- 具体来说系统底层会启动 DocumentsUI 应用, 这个应用拥有 root 权限，它可以跨越所有应用的限制去读取文件，然后通过 ContentProvider 将文件描述符跨进程传递给应用  
- 可以申请 MANAGE_EXTERNAL_STORAGE允许应用访问外部存储的所有文件
- 可以使用Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION 让用户去权限配置页自己配置，授权所有文件读写权限和 MediaStore.Files 表内容访问权限，该Intent操作除了/data和应用私有外均可访问，由AppOps管理。当发送这个 Intent 时，系统会直接跳转到一个专门的设置页面，列表里显示了所有申请了ALL_FILES_ACCESS_PERMISSION权限的应用，用户可以在这里手动点击开关来授权


# Audio Framework

## 音频基础

### 量化

- 使用数字信号来记录声音，将电信号数字化存储。将模拟信号转为电信号的过程称为量化
- 1. 模拟信号：表现为连续平滑的波形，横轴时间纵轴强弱
- 2. 采样：按照一定时间间隔在连续的波上进行采样取值
- 3. 量化：将采样值进行量化处理，记录每个采样的纵坐标的值
- 4. 编码：将每个量化后的样本转换为二进制编码
- 5. 数字信号：将所有样本二进制编码连起来存储在计算机上就形成了数字信号

- 采样大小：一个采样值占用的bits数，常用的值是16bits，这意味着对波形采样时范围是0~65535
- 采样频率：每秒采样的次数，常用的值是44100Hz，当然也可以是8000/16000/32000等，具体根据硬件选择，值越高，数据量越大，声音还原度越高
- 通道：每个方位播放的声音是一个通道，常用的一般是单/双声道以及其他数量的多声道
- 码率：每秒传送的bit数量，比特率越高每秒传输数据越多，声音质量越高

### 编解码

- 常用的音频编解码器包括OPUS>AAC>Vorbis

### 格式

- 音频的原始二进制数据格式是pcm
- aac/mp3/ogg等压缩后的音频格式
- wav是pcm加上44字节头的一种声音文件格式

## 框架构成

- 两个服务：AudioFlinger与AudioPolicyService
    - 1. 这两个服务具体在audioserver进程中运行，负责音频模块的加载。具体来说，加载配置文件，解析音频输入输出硬件信息与HIDL，建立硬件通信的连接
        - 在init进程初始化文件系统，创建deamon的同时也会创建AudioSevver/ServiceManager等服务
        - 另外init进程fork的Zygote是Android进程的父进程
    - 2. AudioFlinger负责向下访问Audio HAL,实现音频PCM数据的混音/输入/输出，以及音量调节
    - 3. AudioPolicyService负责音频策略的调度，管理各种输入输出设备的切换策略，由AudioFlinger的openOutput进行切换操作


- 具体分层：
    - 1. app : 音乐播放，声音录制以及通话等应用层的具体application
    - 2. framework ： 常用的api有MediaPlayer/MediaRecorder, AudioTrack/AudioRecorder, 以及AudioManager & AudioService
        - AudioTrack 能够通过 Binder IPC 来访问 AudioFlinger
    - 3. runtime lib : 上层api作为client调用本层的AudioFlinger/AudioPolicyService（当然不止这些）
    - 4. hal : audio_module包括音频流输入输出与设备管理，audio_policy_module包括具体的设备切换策略接口与客户端交互接口
    - 5. TinyAlsa/AudioDriver ：linux音频架构负责驱动与硬件的交互

## 设备/声卡/轨道

- 输入输出设备device一般有耳机麦克风等。
    - 一个设备上具有相同参数的一组device称为output,这些device属于同一硬件的不同端口
    - 一个output对应一个PlaybackThread线程
    - 一个线程有多个track,一个track对应多个AudioTrack，
- module：硬件操作库，使用hardware module来访问硬件（声卡）
- profile：用来描述output，描述可以支持哪些设备
- streamtype：描述声音类型，在AudioTrack层的AudioSystem.java定义
- strategy：描述使用具体设备和类型的分类方法
- policy : stream之间的影响关系和设备选择的策略

## AudioTrack

- 每一个音频流对应着一个AudioTrack类的一个实例，每个AudioTrack会在创建时注册到 AudioFlinger中，由AudioFlinger把所有的AudioTrack进行混合（Mixer），然后输送到AudioHardware中进行播放，目前Android同时最多可以创建32个音频流，也就是说，Mixer最多会同时处理32个AudioTrack的数据流
- AudioTrack负责将音频数据写入环形缓冲区，AudioFlinger负责从缓冲区读取音频数据，二者类似于生产者-消费者关系，当然也存在读写速度不对等的阻塞问题

## Audio HAL

- 输出流设备决定对应的PlaybackThread的类型，当某种类型的输出流设备被硬件所支持时，对应类型的线程才会被创建
- 一般来说有四种逻辑输出流设备：
    - 1. primaryout：主输出流设备，用于铃声类声音输出，对应着标识为 AUDIOOUTPUTFLAGPRIMARY 的音频流和一个 MixerThread 回放线程实例。该设备强制要求硬件必须支持，并且系统启动时就会创建对应的MixerThread实例
    - 2. lowlatency：低延迟输出流设备，用于按键音、游戏背景音等对时延要求高的声音输出，对应着标识为 AUDIOOUTPUTFLAGFAST 的音频流和一个 MixerThread 回放线程实例
    - 3. deepbuffer：音乐音轨输出流设备，用于音乐等对时延要求不高的声音输出，对应着标识为 AUDIOOUTPUTFLAGDEEP_BUFFER 的音频流和一个 MixerThread 回放线程实例
    - 4. compressoffload：硬解输出流设备，用于需要硬件解码的数据输出，对应着标识为 AUDIOOUTPUTFLAGCOMPRESS_OFFLOAD 的音频流和一个 OffloadThread 回放线程实例

## AudioMixer
- AudioMixer是Android的混音器，通过混音器可以把各个音轨的音频数据混合在一起，然后输出到音频设备
    - 混音以track为源，mainBuffer为目标，frameCount为一次混音长度。AudioMixer最多能维护32个track。track可以对应不同mainBuffer，尽管一般情况下他们的mainBuffer都是同一个









