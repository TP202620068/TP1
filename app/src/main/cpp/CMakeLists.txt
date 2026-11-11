cmake_minimum_required(VERSION 3.31.6)
project(tp1_llama LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "TP1 inference supports Android arm64-v8a only")
endif()
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include("${CMAKE_CURRENT_LIST_DIR}/NativeDependencies.cmake")
tp1_prepare_native_dependencies()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_STATIC OFF CACHE BOOL "" FORCE)
foreach(option LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_TOOLS
        LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_BUILD_MTMD LLAMA_BUILD_UI LLAMA_CONNECT
        LLAMA_OPENSSL LLAMA_LLGUIDANCE LLAMA_SUBPROCESS GGML_NATIVE GGML_BACKEND_DL
        GGML_CPU_ALL_VARIANTS GGML_OPENMP GGML_OPENMP_FETCH GGML_LLAMAFILE GGML_CCACHE
        GGML_VULKAN GGML_OPENCL GGML_BLAS GGML_CUDA GGML_METAL GGML_RPC
        KLEIDIAI_BUILD_EXPERIMENTAL_OPS)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMIT "${TP1_LLAMA_REVISION}" CACHE STRING "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI "${TP1_ROOT}/third_party/kleidiai" CACHE PATH "" FORCE)
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)
add_subdirectory("${TP1_ROOT}/third_party/llama.cpp" build-llama EXCLUDE_FROM_ALL)

add_library(tp1_llama SHARED llama_jni.cpp)
option(TP1_LLAMA_TEST_HOOKS "Enable explicit instrumentation-only controls" OFF)
if(TP1_LLAMA_TEST_HOOKS)
    target_compile_definitions(tp1_llama PRIVATE TP1_LLAMA_TEST_HOOKS=1)
endif()
target_link_libraries(tp1_llama PRIVATE llama android log)
target_compile_options(tp1_llama PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(tp1_llama PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.map"
    "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")
set_property(TARGET tp1_llama APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.map")
