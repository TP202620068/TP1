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

get_filename_component(TP1_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
find_package(Git REQUIRED)
function(require_checkout directory revision)
    if(NOT EXISTS "${directory}/CMakeLists.txt")
        message(FATAL_ERROR "Missing ${directory}. Follow LLAMA_CPP_MANUAL_CHECKS.md first.")
    endif()
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${directory}" rev-parse HEAD
        RESULT_VARIABLE result OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE)
    if(NOT result EQUAL 0 OR NOT actual STREQUAL revision)
        message(FATAL_ERROR "${directory} must be checked out at ${revision}; observed ${actual}")
    endif()
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${directory}" status --porcelain --untracked-files=normal
        RESULT_VARIABLE result OUTPUT_VARIABLE changes OUTPUT_STRIP_TRAILING_WHITESPACE)
    if(NOT result EQUAL 0 OR NOT changes STREQUAL "")
        message(FATAL_ERROR "${directory} must be clean, including untracked files")
    endif()
endfunction()
require_checkout("${TP1_ROOT}/third_party/llama.cpp" "280141e6d4769b1e4424de999437cc72ffac33d6")
require_checkout("${TP1_ROOT}/third_party/kleidiai" "c80157210987eedaab2509638ef5e15950c53a8c")

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
set(LLAMA_BUILD_COMMIT "280141e6d4769b1e4424de999437cc72ffac33d6" CACHE STRING "" FORCE)
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
