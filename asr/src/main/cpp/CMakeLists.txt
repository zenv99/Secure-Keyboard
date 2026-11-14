cmake_minimum_required(VERSION 3.22.1)
project(securekeyboard_asr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp is a git submodule pinned to a reviewed release tag (see third_party/README.md).
set(WHISPER_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp submodule missing. Run: git submodule update --init --recursive")
endif()

# AGP defaults to a Debug CMake build; inference in Debug is unusably slow, so always optimize.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# CPU features (armv8.2-a + dotprod + fp16, i.e. Cortex-A75 / Snapdragon 845 and newer) come from
# -DGGML_CPU_ARM_ARCH in asr/build.gradle.kts so ggml enables its matching kernels.

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper)

add_library(securekeyboard_whisper SHARED whisper_jni.cpp)
target_include_directories(securekeyboard_whisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(securekeyboard_whisper whisper)
target_link_options(securekeyboard_whisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
