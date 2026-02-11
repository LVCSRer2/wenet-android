if(TORCH)
  add_definitions(-DUSE_TORCH)
  if(ANDROID)
    file(GLOB PYTORCH_INCLUDE_DIRS "${build_DIR}/pytorch_android*.aar/headers")
    file(GLOB PYTORCH_LINK_DIRS "${build_DIR}/pytorch_android*.aar/jni/${ANDROID_ABI}")
    find_library(PYTORCH_LIBRARY pytorch_jni
      PATHS ${PYTORCH_LINK_DIRS}
      NO_CMAKE_FIND_ROOT_PATH
    )
    find_library(FBJNI_LIBRARY fbjni
      PATHS ${PYTORCH_LINK_DIRS}
      NO_CMAKE_FIND_ROOT_PATH
    )
    include_directories(${PYTORCH_INCLUDE_DIRS})
  endif()
endif()

if(ONNX)
  add_definitions(-DUSE_ONNX)
  if(ANDROID)
    file(GLOB ONNXRUNTIME_INCLUDE_DIRS "${build_DIR}/onnxruntime-android*.aar/headers")
    file(GLOB ONNXRUNTIME_LINK_DIRS "${build_DIR}/onnxruntime-android*.aar/jni/${ANDROID_ABI}")
    find_library(ONNXRUNTIME_LIBRARY onnxruntime
      PATHS ${ONNXRUNTIME_LINK_DIRS}
      NO_CMAKE_FIND_ROOT_PATH
    )
    include_directories(${ONNXRUNTIME_INCLUDE_DIRS})
  endif()
endif()

if(NNAPI)
  add_definitions(-DUSE_NNAPI)
  if(ANDROID)
    file(GLOB ONNXRUNTIME_INCLUDE_DIRS "${build_DIR}/onnxruntime-android*.aar/headers")
    file(GLOB ONNXRUNTIME_LINK_DIRS "${build_DIR}/onnxruntime-android*.aar/jni/${ANDROID_ABI}")
    find_library(ONNXRUNTIME_LIBRARY onnxruntime
      PATHS ${ONNXRUNTIME_LINK_DIRS}
      NO_CMAKE_FIND_ROOT_PATH
    )
    include_directories(${ONNXRUNTIME_INCLUDE_DIRS})
  endif()
endif()
