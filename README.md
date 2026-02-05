# 一个简单的音频应用 实现音频的播放与录制

- 仅作为了解 Android API 与app层交互和内部封装协作过程
- 只是从app层来看，开发媒体应用应该考虑：
  - 1. 媒体状态与UI的同步
  - 2. 不同来源的相同控制命令的统一处理
  - 3. 针对 Android 应用的健壮性优化

## 概述

- 1. 播放用户授权的某个目录下的音频文件，但不包括该目录的递归子目录，若存在多个文件则自动连续播放
- 2. 录制一段音频并保存在应用私有目录`/storage/emulated/0/Android/data/files/Music/record.wav`

## UI 状态同步

- 使用 ViewModel & MutableLivedata 保存 Activity 中有关功能的状态
- 使用 BroadCast 同步 Play&Record Service 与 Activity 状态更新
- ui控件主要有播放/录制/录制停止按键，状态提示Toast, 通知栏常驻录音状态，以及显示录制音量大小的进度条

## 控制处理

- 使用 MediaSession Callback 处理多媒体按键（耳机按键）

## 应用优化

- 使用 Service 调用 AudioRecord/MediaPlayer/AudioManager 等 api 处理音频播放与录制，以及焦点管理
- 使用 Service 提供后台播放并解决配置更改（屏幕旋转）/返回键触发导致的 Activity 重建问题
- 音频焦点处理了重新获得/永久失去/暂时失去三种情况
- 使用音频焦点与广播处理耳机插拔与来电通话对音频播放/录制的中断，以及与其他应用竞争焦点的情况



