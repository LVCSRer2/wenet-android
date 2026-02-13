#!/bin/bash
set -e

# ============================================================
# WeNet Android 빌드 스크립트
# 사용법: ./build.sh --backend=libtorch|onnxruntime|onnxruntime-nnapi [--install]
# ============================================================

ANDROID_SDK="/home/jieunstage/android-sdk"
JAVA_HOME="$ANDROID_SDK/jdk-17.0.2"
WORK_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$WORK_DIR/wenet/runtime/android"
ASSETS_DIR="$ANDROID_DIR/app/src/main/assets"

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

if [ "$BACKEND" != "libtorch" ] && [ "$BACKEND" != "onnxruntime" ] && [ "$BACKEND" != "onnxruntime-nnapi" ]; then
  echo "오류: --backend=libtorch|onnxruntime|onnxruntime-nnapi 를 지정하세요."
  echo ""
  echo "사용법:"
  echo "  $0 --backend=libtorch              # PyTorch Mobile 버전 빌드"
  echo "  $0 --backend=onnxruntime            # ONNX Runtime (CPU) 빌드"
  echo "  $0 --backend=onnxruntime-nnapi      # ONNX Runtime + NNAPI 빌드"
  echo "  $0 --backend=onnxruntime --install   # 빌드 후 기기에 설치"
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
# 3단계: 백엔드→flavor 매핑
# ----------------------------------------------------------
echo ""
echo "=== 3단계: flavor 매핑 ==="

case "$BACKEND" in
  libtorch)        FLAVOR="Libtorch" ;;
  onnxruntime)     FLAVOR="Onnxruntime" ;;
  onnxruntime-nnapi) FLAVOR="OnnxruntimeNnapi" ;;
esac
echo "  $BACKEND → ${FLAVOR}Debug"

# ----------------------------------------------------------
# 4단계: 모델 파일 복사
# ----------------------------------------------------------
echo ""
echo "=== 4단계: 모델 파일 준비 ==="

# assets 디렉토리에서 모델 파일 정리
rm -f "$ASSETS_DIR/final.zip" "$ASSETS_DIR/encoder.onnx" "$ASSETS_DIR/ctc.onnx"

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

elif [ "$BACKEND" = "onnxruntime" ] || [ "$BACKEND" = "onnxruntime-nnapi" ]; then
  if [ ! -f "$ONNX_MODEL_DIR/encoder.onnx" ]; then
    echo "오류: $ONNX_MODEL_DIR/encoder.onnx 없음"
    echo "먼저 ONNX 모델을 export 하세요."
    exit 1
  fi
  cp "$ONNX_MODEL_DIR/encoder.quant.onnx" "$ASSETS_DIR/encoder.onnx"
  cp "$ONNX_MODEL_DIR/ctc.quant.onnx" "$ASSETS_DIR/ctc.onnx"
  cp "$WORK_DIR/model/units.txt" "$ASSETS_DIR/"
  echo "  assets ← encoder.onnx (quantized, $(du -h "$ASSETS_DIR/encoder.onnx" | cut -f1))"
  echo "  assets ← ctc.onnx (quantized, $(du -h "$ASSETS_DIR/ctc.onnx" | cut -f1))"
fi
echo "  assets ← units.txt"

# ----------------------------------------------------------
# 5단계: Gradle 빌드 (flavor별 독립 빌드)
# ----------------------------------------------------------
echo ""
echo "=== 5단계: Gradle 빌드 ==="
cd "$ANDROID_DIR"
echo "sdk.dir=$ANDROID_SDK" > local.properties

./gradlew assemble${FLAVOR}Debug

FLAVOR_LOWER=$(echo "$FLAVOR" | sed 's/./\L&/')
APK="$ANDROID_DIR/app/build/outputs/apk/${FLAVOR_LOWER}/debug/app-${FLAVOR_LOWER}-debug.apk"
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
