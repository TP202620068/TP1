cmake_minimum_required(VERSION 3.31.6)
option(TP1_NATIVE_OFFLINE "Do not download missing native sources" OFF)

find_package(Git REQUIRED)
get_filename_component(TP1_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(TP1_LLAMA_REVISION "280141e6d4769b1e4424de999437cc72ffac33d6")
set(TP1_KLEIDIAI_REVISION "c80157210987eedaab2509638ef5e15950c53a8c")

function(require_checkout directory repository revision)
    get_filename_component(parent "${directory}" DIRECTORY)
    get_filename_component(name "${directory}" NAME)
    set(staging "${parent}/../.gradle/tp1-native/${name}.tmp")
    file(LOCK "${parent}/../.gradle/tp1-native/checkout.lock" GUARD FUNCTION TIMEOUT 360)
    set(git "${GIT_EXECUTABLE}" --no-optional-locks -c core.hooksPath=/dev/null -c core.fsmonitor=false)
    set(source "${directory}")

    if(NOT EXISTS "${directory}" AND NOT IS_SYMLINK "${directory}")
        if(TP1_NATIVE_OFFLINE)
            message(FATAL_ERROR "Missing ${directory}; build once without --offline to download native sources")
        endif()
        message(STATUS "Downloading ${repository} at ${revision}")
        file(MAKE_DIRECTORY "${parent}")
        file(REMOVE_RECURSE "${staging}")
        execute_process(COMMAND ${git} init --quiet "${staging}"
            COMMAND_ERROR_IS_FATAL ANY)
        execute_process(COMMAND "${CMAKE_COMMAND}" -E env GIT_TERMINAL_PROMPT=0
            ${git} -C "${staging}" fetch --quiet --depth 1 "${repository}" "${revision}"
            TIMEOUT 300 COMMAND_ERROR_IS_FATAL ANY)
        execute_process(COMMAND ${git} -C "${staging}" checkout --quiet --detach "${revision}"
            COMMAND_ERROR_IS_FATAL ANY)
        set(source "${staging}")
    endif()

    if(NOT EXISTS "${source}/.git" OR NOT EXISTS "${source}/CMakeLists.txt")
        message(FATAL_ERROR "${source} is not a native dependency checkout; move it aside and rebuild")
    endif()
    execute_process(COMMAND ${git} -C "${source}" rev-parse HEAD
        RESULT_VARIABLE result OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE)
    if(NOT result EQUAL 0 OR NOT actual STREQUAL revision)
        message(FATAL_ERROR "${source} must be checked out at ${revision}; observed ${actual}. Existing sources were not changed.")
    endif()
    execute_process(COMMAND ${git} -C "${source}" status --porcelain --untracked-files=normal
        RESULT_VARIABLE result OUTPUT_VARIABLE changes OUTPUT_STRIP_TRAILING_WHITESPACE)
    if(NOT result EQUAL 0 OR NOT changes STREQUAL "")
        message(FATAL_ERROR "${source} must be clean, including untracked files. Existing sources were not changed.")
    endif()
    if(NOT source STREQUAL directory)
        if(EXISTS "${directory}" OR IS_SYMLINK "${directory}")
            message(FATAL_ERROR "${directory} appeared during download; existing sources were not changed")
        endif()
        # CMake 3.31's NO_REPLACE uses hard links on Unix, which cannot move directories.
        file(RENAME "${source}" "${directory}")
    endif()
endfunction()

function(tp1_prepare_native_dependencies)
    require_checkout("${TP1_ROOT}/third_party/llama.cpp"
        "https://github.com/ggml-org/llama.cpp.git" "${TP1_LLAMA_REVISION}")
    require_checkout("${TP1_ROOT}/third_party/kleidiai"
        "https://github.com/ARM-software/kleidiai.git" "${TP1_KLEIDIAI_REVISION}")
endfunction()

if(CMAKE_SCRIPT_MODE_FILE STREQUAL CMAKE_CURRENT_LIST_FILE)
    tp1_prepare_native_dependencies()
endif()
