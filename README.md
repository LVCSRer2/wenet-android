# WeNet Android - Dual Backend Build

WeNet 음성인식 모델을 Android에 배포하는 프로젝트입니다.
**LibTorch (PyTorch Mobile)**와 **ONNX Runtime** 두 가지 백엔드를 지원합니다.

## 프로젝트 구조

```
wenet-android/
├── build.sh                          # 통합 빌드 스크립트
├── README.md
├── gigaspeech_u2pp_conformer_libtorch_quant/  # LibTorch 모델 (별도 다운로드)
│   ├── final.zip
│   └── units.txt
├── onnx_model/                       # ONNX 모델 (별도 export)
│   ├── encoder.onnx (or encoder.quant.onnx)
│   ├── ctc.onnx (or ctc.quant.onnx)
│   ├── decoder.onnx (or decoder.quant.onnx)
│   └── units.txt
└── wenet/
    └── runtime/
        ├── android/                  # Android 프로젝트
        │   └── app/
        │       ├── build.gradle
        │       └── src/main/
        │           ├── assets/       # 모델 파일 (빌드 시 복사됨)
        │           ├── cpp/          # JNI / C++ 코드
        │           │   ├── wenet.cc
        │           │   └── cmake/libtorch.cmake
        │           └── java/         # Java 소스
        └── core/                     # C++ 코어 라이브러리 (심링크 참조)
            ├── decoder/
            ├── frontend/
            ├── post_processor/
            └── utils/
```

## 사전 요구사항

- **Android SDK** (API 30+)
- **NDK** 21.1.6352462
- **CMake** 3.18.1
- **JDK** 17
- **모델 파일** (아래 참조)

## 모델 준비

### LibTorch 백엔드
`gigaspeech_u2pp_conformer_libtorch_quant/` 디렉토리에 다음 파일 필요:
- `final.zip` — 양자화된 TorchScript 모델
- `units.txt` — 토큰 사전

### ONNX Runtime 백엔드
`onnx_model/` 디렉토리에 다음 파일 필요:
- `encoder.onnx` — 인코더 모델 (global_cmvn 포함 필수)
- `ctc.onnx` — CTC 디코더
- `decoder.onnx` — Attention 디코더 (opset 13, IR version ≤ 8)
- `units.txt` — 토큰 사전

ONNX 모델 export 시 주의사항:
- **global_cmvn**이 encoder에 반드시 포함되어야 합니다 (없으면 인식 불가)
- decoder는 **opset 13**으로 export해야 합니다 (ONNX Runtime 1.13.1 호환)
- 양자화 모델 사용 시 `.quant.onnx` 파일을 `.onnx`로 이름 변경하여 복사

## 빌드 방법

### 빌드 스크립트 설정

`build.sh` 상단의 경로를 환경에 맞게 수정하세요:
```bash
ANDROID_SDK="/path/to/android-sdk"
JAVA_HOME="$ANDROID_SDK/jdk-17.0.2"
```

### LibTorch 버전 빌드

```bash
./build.sh --backend=libtorch
```

### ONNX Runtime 버전 빌드

```bash
./build.sh --backend=onnxruntime
```

### 빌드 + 디바이스 설치

```bash
./build.sh --backend=onnxruntime --install
```

## 빌드 스크립트 동작

`build.sh`는 `--backend` 인자에 따라 다음 파일들을 자동으로 전환합니다:

| 파일 | LibTorch | ONNX Runtime |
|------|----------|--------------|
| `app/build.gradle` | `pytorch_android:1.13.0` | `onnxruntime-android:1.13.1` |
| `CMakeLists.txt` | `TORCH=ON, ONNX=OFF` | `TORCH=OFF, ONNX=ON` |
| `MainActivity.java` | `final.zip, units.txt` | `encoder.onnx, ctc.onnx, decoder.onnx, units.txt` |
| `wenet.cc` | `#ifdef USE_TORCH` 활성화 | `#ifdef USE_ONNX` 활성화 |
| Assets | `final.zip` 복사 | `encoder/ctc/decoder.onnx` 복사 |

## C++ 코드 구조 (듀얼 백엔드)

`wenet.cc`에서 `#ifdef`로 백엔드를 분기합니다:

```cpp
#ifdef USE_ONNX
  #include "decoder/onnx_asr_model.h"
  // OnnxAsrModel 사용, 디렉토리 경로로 모델 로드
#endif
#ifdef USE_TORCH
  #include "torch/script.h"
  #include "decoder/torch_asr_model.h"
  // TorchAsrModel 사용, final.zip 파일 경로로 모델 로드
#endif
```

`cmake/libtorch.cmake`에서 각 백엔드의 라이브러리/헤더를 설정합니다.

## 성능 비교 (Galaxy Note 9, u2pp_conformer)

| 항목 | LibTorch (양자화) | ONNX Runtime (Full) | ONNX Runtime (양자화) |
|------|-------------------|---------------------|----------------------|
| **APK 크기** | 201 MB | 467 MB | **114 MB** |
| **모델 로딩** | ~3.4초 | ~5.1초 | ~4.8초 |
| **메모리 (PSS)** | 409 MB | 703 MB | **390 MB** |
| **Native Heap** | 346 MB | 650 MB | **319 MB** |
| **인식 품질** | 정상 | 정상 | 정상 |

## Slack Webhook 연동

녹음 완료 후 음성인식 결과를 Slack 채널로 자동 전송하는 기능입니다.

### 설정 방법
1. 앱 하단의 **Settings** 버튼 탭
2. Slack Incoming Webhook URL 입력 후 **Save**
3. 녹음 → Stop → Slack 채널에 `📝 [녹음명] + 인식결과` 메시지 수신

### 관련 파일
| 파일 | 역할 |
|------|------|
| `SlackWebhookSender.java` | HttpsURLConnection으로 Slack webhook POST (백그라운드 스레드) |
| `SettingsActivity.java` | Webhook URL 입력/저장 화면 (SharedPreferences) |
| `activity_settings.xml` | 설정 화면 레이아웃 |

## 주요 수정 사항 (원본 WeNet 대비)

1. **`wenet.cc`**: `#ifdef USE_ONNX` / `#ifdef USE_TORCH` 듀얼 백엔드 지원
2. **`cmake/libtorch.cmake`**: TORCH/ONNX 조건부 라이브러리 설정 통합
3. **`decoder/torch_asr_model.h/.cc`**: Android에서 `torch/torch.h` 사용 불가 → `#if !defined(IOS) && !defined(ANDROID)` 가드 추가
4. **`decoder/params.h`**: `InitEngineThreads()` 호출에 Android 가드 추가
5. **`app/build.gradle`**: `configureCMake` 태스크 의존성 + JitPack 레포지토리 추가
6. **`cmake/wetextprocessing.cmake`**: `CMAKE_SHARED_LINKER_FLAGS` + `build_DIR` 전달 수정

## 라이선스

원본 WeNet 프로젝트의 Apache License 2.0을 따릅니다.
- WeNet: https://github.com/wenet-e2e/wenet
