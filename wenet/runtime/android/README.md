# WeNet On-device ASR Android Demo

This Android demo shows we can run on-device streaming ASR with WeNet. You can download our prebuilt APK or build your APK from source code.

## Prebuilt APK

* [Chinese ASR Demo APK, with model trained on AIShell data](http://mobvoi-speech-public.ufile.ucloud.cn/public/wenet/aishell/20210202_app.apk)
* [English ASR Demo APK, with model trained on GigaSpeech data](http://mobvoi-speech-public.ufile.ucloud.cn/public/wenet/gigaspeech/20210823_app.apk)

## Build your APK from source code

### 1) Build model

You can use our pretrained model (click the following link to download):

[中文(WenetSpeech)](https://wenet-1256283475.cos.ap-shanghai.myqcloud.com/models/wenetspeech/wenetspeech_u2pp_conformer_libtorch_quant.tar.gz)
| [English(GigaSpeech)](https://wenet-1256283475.cos.ap-shanghai.myqcloud.com/models/gigaspeech/gigaspeech_u2pp_conformer_libtorch_quant.tar.gz)

Or you can train your own model using WeNet training pipeline on your data.

### 2) Build APK

When your model is ready, put `final.zip` and `units.txt` into Android assets (`app/src/main/assets`) folder,
then just build and run the APK. Here is a gif demo, which shows how our on-device streaming e2e ASR runs with low latency.
Please note the wifi and data has been disabled in the demo so there is no network connection ^\_^.

![Runtime android demo](../../../../docs/images/runtime_android.gif)

## 녹음 저장/재생 및 카라오케 하이라이트

녹음한 오디오를 단어별 타임스탬프와 함께 저장하고, 재생 시 카라오케 스타일로 하이라이트하는 기능입니다.

### 기능

- **녹음 저장**: 녹음 시 raw PCM (16kHz, 16-bit, mono)과 단어별 타임스탬프가 포함된 `result.json`이 함께 저장됩니다.
- **카라오케 재생**: 재생 중 현재 발화 중인 단어가 노란색으로 하이라이트되며, 오디오 위치와 동기화됩니다.
- **단어 터치 → 이동**: 텍스트의 단어를 터치하면 해당 위치로 오디오가 이동하여 재생됩니다.
- **슬라이드바(SeekBar)**: 드래그하여 녹음 내 원하는 위치로 이동할 수 있습니다.
- **녹음 목록**: "Recordings" 버튼으로 과거 녹음을 탐색하고 재생할 수 있습니다.

### 저장 구조

녹음은 앱 내부 저장소에 저장됩니다:

```
{filesDir}/recordings/{yyyyMMdd_HHmmss}/
  ├── audio.pcm        # raw PCM (16kHz, 16-bit, mono)
  └── result.json      # [{"w":"단어","s":시작ms,"e":종료ms}, ...]
```

### 사용 방법

1. **녹음**: "Start Record"를 눌러 녹음을 시작합니다. 오디오는 ASR 엔진으로 전달되는 동시에 `audio.pcm`에 저장됩니다.
2. **중지**: "Stop Record"를 누르면 단어별 타임스탬프(`word_pieces`)가 포함된 최종 인식 결과가 `result.json`으로 저장됩니다.
3. **재생**: 녹음 완료 후 재생 컨트롤이 자동으로 나타납니다. "Play"를 누르면 카라오케 하이라이트와 함께 오디오가 재생됩니다.
4. **목록**: "Recordings" 버튼으로 이전 녹음을 선택하여 다시 재생할 수 있습니다.

### 구현 참고

- 단어별 타임스탬프는 WeNet 디코더의 `unit_table` (`units.txt`의 `symbol_table`과 동일)을 통해 생성됩니다.
- 타임스탬프는 `CtcPrefixBeamSearch`의 프레임 레벨 정렬에서 가져오며, `asr_decoder.cc`에서 밀리초로 변환됩니다.
- JNI 함수 `getTimedResult()`가 타임스탬프를 JSON 배열로 반환합니다.

## Compute the RTF

Step 1, connect your Android phone, and use `adb push` command to push your model, wav scp, and waves to the sdcard.

Step 2, build the binary and the APK with Android Studio directly, or with the commands as follows:

``` sh
cd runtime/android
./gradlew build
```

Step 3, push your binary and the dynamic library to `/data/local/tmp` as follows:

``` sh
adb push app/.cxx/cmake/release/arm64-v8a/decoder_main /data/local/tmp
adb push app/build/pytorch_android-1.10.0.aar/jni/arm64-v8a/* /data/local/tmp
```

Step 4, change to the directory `/data/local/tmp` of your phone, and export the library path by:

``` sh
adb shell
cd /data/local/tmp
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:.
```

Step 5, execute the same command as the [x86 demo](../../../libtorch) to run the binary to decode and compute the RTF.
