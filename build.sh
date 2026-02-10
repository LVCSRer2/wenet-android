#!/bin/bash
set -e

# ============================================================
# WeNet Android 빌드 스크립트
# 사용법: ./build.sh --backend=libtorch|onnxruntime [--install]
# ============================================================

ANDROID_SDK="/home/jieunstage/android-sdk"
JAVA_HOME="$ANDROID_SDK/jdk-17.0.2"
WORK_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$WORK_DIR/wenet/runtime/android"
ASSETS_DIR="$ANDROID_DIR/app/src/main/assets"
CPP_DIR="$ANDROID_DIR/app/src/main/cpp"

export JAVA_HOME
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK/platform-tools:$ANDROID_SDK/cmdline-tools/latest/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# ----------------------------------------------------------
# 인자 파싱
# ----------------------------------------------------------
BACKEND=""
INSTALL=false

for arg in "$@"; do
  case $arg in
    --backend=*)
      BACKEND="${arg#*=}"
      ;;
    --install)
      INSTALL=true
      ;;
    *)
      echo "알 수 없는 인자: $arg"
      echo "사용법: $0 --backend=libtorch|onnxruntime [--install]"
      exit 1
      ;;
  esac
done

if [ "$BACKEND" != "libtorch" ] && [ "$BACKEND" != "onnxruntime" ]; then
  echo "오류: --backend=libtorch 또는 --backend=onnxruntime 을 지정하세요."
  echo ""
  echo "사용법:"
  echo "  $0 --backend=libtorch            # PyTorch Mobile 버전 빌드"
  echo "  $0 --backend=onnxruntime          # ONNX Runtime 버전 빌드"
  echo "  $0 --backend=onnxruntime --install # 빌드 후 기기에 설치"
  exit 1
fi

echo "============================================"
echo "  WeNet Android 빌드 ($BACKEND)"
echo "============================================"

# ----------------------------------------------------------
# 1단계: NDK / CMake 설치
# ----------------------------------------------------------
echo ""
echo "=== 1단계: NDK / CMake 확인 ==="
if [ ! -d "$ANDROID_SDK/ndk/21.1.6352462" ]; then
  echo "y" | sdkmanager --sdk_root="$ANDROID_SDK" \
    "ndk;21.1.6352462" "cmake;3.18.1" 2>&1 | tail -3
else
  echo "이미 설치됨."
fi

# ----------------------------------------------------------
# 2단계: WeNet 소스 확인
# ----------------------------------------------------------
echo ""
echo "=== 2단계: WeNet 소스 확인 ==="
if [ ! -d "$WORK_DIR/wenet" ]; then
  cd "$WORK_DIR"
  git clone --depth 1 https://github.com/wenet-e2e/wenet.git
else
  echo "이미 존재함."
fi

# ----------------------------------------------------------
# 3단계: 백엔드별 설정 전환
# ----------------------------------------------------------
echo ""
echo "=== 3단계: $BACKEND 설정 적용 ==="

# --- 3a: build.gradle 수정 ---
GRADLE_FILE="$ANDROID_DIR/app/build.gradle"

if [ "$BACKEND" = "libtorch" ]; then
  # 의존성: pytorch_android + C10 cppFlags
  sed -i "s|com.microsoft.onnxruntime:onnxruntime-android:[^']*|org.pytorch:pytorch_android:1.13.0|g" "$GRADLE_FILE"
  # cppFlags에 C10 플래그 추가 (없으면)
  if ! grep -q "DC10_USE_GLOG" "$GRADLE_FILE"; then
    sed -i 's|cppFlags "-std=c++14", "-DANDROID", "-Wno-c++11-narrowing", "-fexceptions"|cppFlags "-std=c++14", "-DANDROID", "-Wno-c++11-narrowing", "-fexceptions", "-DC10_USE_GLOG", "-DC10_USE_MINIMAL_GLOG"|' "$GRADLE_FILE"
  fi
  echo "  build.gradle → pytorch_android:1.13.0"

elif [ "$BACKEND" = "onnxruntime" ]; then
  # 의존성: onnxruntime-android
  sed -i "s|org.pytorch:pytorch_android:[^']*|com.microsoft.onnxruntime:onnxruntime-android:1.13.1|g" "$GRADLE_FILE"
  # cppFlags에서 C10 플래그 제거
  sed -i 's|, "-DC10_USE_GLOG", "-DC10_USE_MINIMAL_GLOG"||g' "$GRADLE_FILE"
  echo "  build.gradle → onnxruntime-android:1.13.1"
fi

# --- 3b: CMakeLists.txt 옵션 전환 ---
CMAKE_FILE="$CPP_DIR/CMakeLists.txt"

if [ "$BACKEND" = "libtorch" ]; then
  sed -i 's|option(TORCH "whether to build with Torch" OFF)|option(TORCH "whether to build with Torch" ON)|' "$CMAKE_FILE"
  sed -i 's|option(ONNX "whether to build with ONNX" ON)|option(ONNX "whether to build with ONNX" OFF)|' "$CMAKE_FILE"
  echo "  CMakeLists.txt → TORCH=ON, ONNX=OFF"

elif [ "$BACKEND" = "onnxruntime" ]; then
  sed -i 's|option(TORCH "whether to build with Torch" ON)|option(TORCH "whether to build with Torch" OFF)|' "$CMAKE_FILE"
  sed -i 's|option(ONNX "whether to build with ONNX" OFF)|option(ONNX "whether to build with ONNX" ON)|' "$CMAKE_FILE"
  echo "  CMakeLists.txt → TORCH=OFF, ONNX=ON"
fi

# --- 3c: MainActivity.java 리소스 목록 전환 ---
JAVA_FILE="$ANDROID_DIR/app/src/main/java/com/mobvoi/wenet/MainActivity.java"

if [ "$BACKEND" = "libtorch" ]; then
  sed -i 's|"encoder.onnx", "ctc.onnx", "decoder.onnx", "units.txt"|"final.zip", "units.txt"|' "$JAVA_FILE"
  echo "  MainActivity.java → final.zip, units.txt"

elif [ "$BACKEND" = "onnxruntime" ]; then
  sed -i 's|"final.zip", "units.txt"|"encoder.onnx", "ctc.onnx", "decoder.onnx", "units.txt"|' "$JAVA_FILE"
  echo "  MainActivity.java → encoder.onnx, ctc.onnx, decoder.onnx, units.txt"
fi

# ----------------------------------------------------------
# 4단계: 모델 파일 복사
# ----------------------------------------------------------
echo ""
echo "=== 4단계: 모델 파일 준비 ==="

# assets 디렉토리에서 모델 파일 정리
rm -f "$ASSETS_DIR/final.zip" "$ASSETS_DIR/encoder.onnx" "$ASSETS_DIR/ctc.onnx" "$ASSETS_DIR/decoder.onnx"

LIBTORCH_MODEL_DIR="$WORK_DIR/gigaspeech_u2pp_conformer_libtorch_quant"
ONNX_MODEL_DIR="$WORK_DIR/onnx_model"

if [ "$BACKEND" = "libtorch" ]; then
  if [ ! -f "$LIBTORCH_MODEL_DIR/final.zip" ]; then
    echo "오류: $LIBTORCH_MODEL_DIR/final.zip 없음"
    echo "먼저 모델을 다운로드하세요."
    exit 1
  fi
  cp "$LIBTORCH_MODEL_DIR/final.zip" "$ASSETS_DIR/"
  cp "$LIBTORCH_MODEL_DIR/units.txt" "$ASSETS_DIR/"
  echo "  assets ← final.zip ($(du -h "$ASSETS_DIR/final.zip" | cut -f1))"

elif [ "$BACKEND" = "onnxruntime" ]; then
  if [ ! -f "$ONNX_MODEL_DIR/encoder.onnx" ]; then
    echo "오류: $ONNX_MODEL_DIR/encoder.onnx 없음"
    echo "먼저 ONNX 모델을 export 하세요."
    exit 1
  fi
  cp "$ONNX_MODEL_DIR/encoder.onnx" "$ASSETS_DIR/"
  cp "$ONNX_MODEL_DIR/ctc.onnx" "$ASSETS_DIR/"
  cp "$ONNX_MODEL_DIR/decoder.onnx" "$ASSETS_DIR/"
  echo "  assets ← encoder.onnx ($(du -h "$ASSETS_DIR/encoder.onnx" | cut -f1))"
  echo "  assets ← ctc.onnx ($(du -h "$ASSETS_DIR/ctc.onnx" | cut -f1))"
  echo "  assets ← decoder.onnx ($(du -h "$ASSETS_DIR/decoder.onnx" | cut -f1))"
fi
echo "  assets ← units.txt"

# ----------------------------------------------------------
# 5단계: 클린 빌드
# ----------------------------------------------------------
echo ""
echo "=== 5단계: Gradle 빌드 ==="
cd "$ANDROID_DIR"
echo "sdk.dir=$ANDROID_SDK" > local.properties

# CMake 캐시 삭제 (백엔드 전환 시 필수)
rm -rf app/.cxx app/build

./gradlew assembleDebug

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "=== 빌드 완료 ==="
echo "  APK: $APK"
echo "  크기: $(du -h "$APK" | cut -f1)"
echo "  백엔드: $BACKEND"

# ----------------------------------------------------------
# 6단계: 설치 (선택)
# ----------------------------------------------------------
if [ "$INSTALL" = true ]; then
  echo ""
  echo "=== 6단계: 기기에 설치 ==="
  adb install -r "$APK"
  echo "설치 완료. 앱을 실행합니다..."
  adb shell am start -n com.mobvoi.wenet/.MainActivity
fi
