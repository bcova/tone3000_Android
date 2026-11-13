cmake_minimum_required(VERSION 3.22.1)
project(ampengine CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NeuralAmpModelerCore -> `nam_core`
include(${CMAKE_CURRENT_SOURCE_DIR}/nam.cmake)

# Oboe comes from the com.google.oboe:oboe Gradle dependency (Prefab).
find_package(oboe REQUIRED CONFIG)

add_library(ampengine SHARED
  native-lib.cpp
  AudioEngine.cpp
  engine/EngineCore.cpp
  engine/Recorder.cpp)

target_include_directories(ampengine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(ampengine PRIVATE -O3 -Wall)
target_link_libraries(ampengine PRIVATE nam_core oboe::oboe log)

# Support 16 KB memory pages (required for new Play Store apps on Android 15+).
target_link_options(ampengine PRIVATE "-Wl,-z,max-page-size=16384")
