# Desktop (Linux/macOS) tests for the platform-independent audio engine.
# These do NOT need Android. Build and run:
#   cmake -S native-tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/native-tests -j
#   ./build/native-tests/engine_test
cmake_minimum_required(VERSION 3.22)
project(engine_tests CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
include(${REPO_ROOT}/app/src/main/cpp/nam.cmake)

find_package(Threads REQUIRED)

add_executable(engine_test
  engine_test.cpp
  ${REPO_ROOT}/app/src/main/cpp/engine/EngineCore.cpp
  ${REPO_ROOT}/app/src/main/cpp/engine/Recorder.cpp
)
target_include_directories(engine_test PRIVATE ${REPO_ROOT}/app/src/main/cpp/engine)
target_link_libraries(engine_test PRIVATE nam_core Threads::Threads)
target_compile_definitions(engine_test PRIVATE
  NAM_EXAMPLE_MODELS="${NAM_DIR}/example_models")
