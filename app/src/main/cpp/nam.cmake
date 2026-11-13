# Builds NeuralAmpModelerCore (git submodule in third_party/) as an object library
# target called `nam_core`. Shared by the Android build and the desktop tests.

get_filename_component(NAM_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/NeuralAmpModelerCore" ABSOLUTE)

if(NOT EXISTS "${NAM_DIR}/NAM/get_dsp.h" OR NOT EXISTS "${NAM_DIR}/Dependencies/eigen/Eigen/Dense")
  message(FATAL_ERROR
    "NeuralAmpModelerCore is missing. From the repo root run:\n"
    "  git submodule update --init --recursive")
endif()

file(GLOB NAM_SOURCES "${NAM_DIR}/NAM/*.cpp" "${NAM_DIR}/NAM/*/*.cpp")

# OBJECT (not STATIC): NAM registers model types from static initializers,
# which a static library would let the linker drop.
add_library(nam_core OBJECT ${NAM_SOURCES})
target_include_directories(nam_core PUBLIC "${NAM_DIR}")
target_include_directories(nam_core SYSTEM PUBLIC
  "${NAM_DIR}/Dependencies/eigen"
  "${NAM_DIR}/Dependencies/nlohmann")
target_compile_definitions(nam_core PUBLIC
  NAM_SAMPLE_FLOAT # process audio as 32-bit float, like Oboe
  NAM_ENABLE_A2_FAST) # optimized path for newer "A2" captures
target_compile_features(nam_core PUBLIC cxx_std_20)

# Neural nets are unusably slow without optimization, so always optimize NAM,
# even in Debug builds of the app.
target_compile_options(nam_core PRIVATE -O3 -w)
