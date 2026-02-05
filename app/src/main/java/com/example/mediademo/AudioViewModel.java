package com.example.mediademo;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class AudioViewModel extends ViewModel {
    public MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);

    public MutableLiveData<String> statusText = new MutableLiveData<>("状态：等待录音");

    public MutableLiveData<Boolean> isPlaying = new MutableLiveData<>(false);

    public void updateRecordingState (boolean recording) {
        isRecording.setValue(recording);
        if (recording) {
            statusText.setValue("状态：正在录制（Service）");
        }
        else {
            statusText.setValue("状态：录音已停止并保存");
        }
    }


}
