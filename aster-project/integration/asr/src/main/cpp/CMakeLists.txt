cmake_minimum_required(VERSION 3.22.1)
project(aster_asr)

# Add whisper.cpp only after recording an exact reviewed commit:
# add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp
#                  ${CMAKE_CURRENT_BINARY_DIR}/whisper)
# Add a narrow JNI bridge against that pinned API. CPU-only arm64 first.
