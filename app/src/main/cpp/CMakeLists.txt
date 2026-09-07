cmake_minimum_required(VERSION 3.22.1)
project(itantra_mt C CXX)

# libitantra_mt — JNI adapter for offline HI<->EN translation via the ONNX
# Runtime bundled inside sherpa-onnx. It dlopens libonnxruntime.so at runtime
# and uses the ORT C API (no second onnxruntime library is packaged).

# Real SentencePiece inference (Apache-2.0/MIT) for Marian-exact tokenization.
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TRAINING OFF CACHE BOOL "" FORCE)
set(SPM_ANDROID_NO_CLI ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/sentencepiece sentencepiece-build)

add_library(itantra_mt SHARED nnmt_jni.cpp)

target_compile_features(itantra_mt PRIVATE cxx_std_14)

# The vendored onnxruntime_c_api.h (#define NO_EXCEPTION patched to be
# C++-friendly — exception specs are not allowed in typedefs on this NDK clang).
target_compile_options(itantra_mt PRIVATE
    -Wall
    -fvisibility=hidden
    -Wno-unused-result
)

target_include_directories(itantra_mt PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/sentencepiece/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/sentencepiece/third_party
)

find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(itantra_mt ${log-lib} ${dl-lib} sentencepiece-static)

set_target_properties(itantra_mt PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)