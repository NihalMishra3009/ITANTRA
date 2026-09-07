cmake_minimum_required(VERSION 3.22.1)
project(itantra_mt C CXX)

add_library(itantra_mt SHARED nnmt_jni.cpp)

target_compile_features(itantra_mt PRIVATE cxx_std_14)
target_compile_options(itantra_mt PRIVATE -Wall -fvisibility=hidden)
target_include_directories(itantra_mt PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime/include
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_LLVM_TOOLCHAIN}/sysroot/usr/include
)

find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(itantra_mt ${log-lib} ${dl-lib})

# Link against the ONNX Runtime shared lib already bundled by sherpa-onnx.
# We dlopen it by name at runtime; no static link is performed.
set_target_properties(itantra_mt PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
