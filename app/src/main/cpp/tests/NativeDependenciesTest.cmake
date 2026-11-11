cmake_minimum_required(VERSION 3.31.6)
include("${CMAKE_CURRENT_LIST_DIR}/../NativeDependencies.cmake")

if(DEFINED TEST_CHECKOUT)
    require_checkout("${TEST_CHECKOUT}" "${TEST_REPOSITORY}" "${TEST_REVISION}")
    return()
endif()

string(RANDOM LENGTH 12 ALPHABET "0123456789abcdef" identifier)
set(root "${CMAKE_CURRENT_BINARY_DIR}/.gradle/native dependencies test-${identifier}")
if(EXISTS "${root}")
    message(FATAL_ERROR "Test directory already exists: ${root}")
endif()
set(repository "${root}/origin")
set(checkout "${root}/third_party/checkout")
set(contents "cmake_minimum_required(VERSION 3.31.6)\nproject(fixture NONE)\n")
file(MAKE_DIRECTORY "${repository}")
file(WRITE "${repository}/CMakeLists.txt" "${contents}")
set(git "${GIT_EXECUTABLE}" -c core.hooksPath=/dev/null -c core.fsmonitor=false)
execute_process(COMMAND ${git} init --quiet "${repository}" COMMAND_ERROR_IS_FATAL ANY)
execute_process(COMMAND ${git} -C "${repository}" add CMakeLists.txt COMMAND_ERROR_IS_FATAL ANY)
execute_process(COMMAND ${git} -C "${repository}" -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false
    commit --quiet -m fixture COMMAND_ERROR_IS_FATAL ANY)
execute_process(COMMAND ${git} -C "${repository}" rev-parse HEAD
    OUTPUT_VARIABLE revision OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)

function(check_case name succeeds)
    execute_process(COMMAND "${CMAKE_COMMAND}"
        "-DTEST_CHECKOUT=${checkout}" "-DTEST_REPOSITORY=${repository}" "-DTEST_REVISION=${revision}"
        "-DTP1_NATIVE_OFFLINE=${TP1_NATIVE_OFFLINE}" -P "${CMAKE_CURRENT_LIST_FILE}"
        RESULT_VARIABLE result OUTPUT_VARIABLE output ERROR_VARIABLE error)
    if((succeeds AND NOT result EQUAL 0) OR (NOT succeeds AND result EQUAL 0))
        message(FATAL_ERROR "${name} failed: ${result}\n${output}\n${error}\nFixtures: ${root}")
    endif()
    message(STATUS "PASS: ${name}")
endfunction()

set(TP1_NATIVE_OFFLINE ON)
check_case("missing offline checkout is rejected" FALSE)
if(EXISTS "${checkout}")
    message(FATAL_ERROR "Offline mode created a checkout")
endif()

set(TP1_NATIVE_OFFLINE OFF)
set(repository "${root}/missing-origin")
check_case("failed download does not publish a partial checkout" FALSE)
if(EXISTS "${checkout}")
    message(FATAL_ERROR "Failed download created a final checkout")
endif()

set(repository "${root}/origin")
check_case("retry downloads the exact commit" TRUE)

set(repository "${root}/missing-origin")
check_case("valid checkout does not contact the remote" TRUE)
set(TP1_NATIVE_OFFLINE ON)
check_case("valid checkout works offline" TRUE)

set(expected_revision "${revision}")
set(revision "0000000000000000000000000000000000000000")
check_case("wrong revision is rejected" FALSE)
execute_process(COMMAND ${git} -C "${checkout}" rev-parse HEAD
    OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
if(NOT actual STREQUAL expected_revision)
    message(FATAL_ERROR "Existing checkout revision changed")
endif()
set(revision "${expected_revision}")

file(APPEND "${checkout}/CMakeLists.txt" "# local change\n")
check_case("tracked local changes are rejected" FALSE)
file(READ "${checkout}/CMakeLists.txt" actual_contents)
if(NOT actual_contents STREQUAL "${contents}# local change\n")
    message(FATAL_ERROR "Local changes were overwritten")
endif()
file(WRITE "${checkout}/CMakeLists.txt" "${contents}")
file(WRITE "${checkout}/local.txt" "keep me\n")
check_case("untracked local files are rejected" FALSE)
file(READ "${checkout}/local.txt" local_contents)
if(NOT local_contents STREQUAL "keep me\n")
    message(FATAL_ERROR "Untracked file was changed")
endif()

set(checkout "${root}/third_party/invalid")
file(MAKE_DIRECTORY "${checkout}")
file(WRITE "${checkout}/CMakeLists.txt" "${contents}")
check_case("non-Git directory is rejected" FALSE)
if(EXISTS "${checkout}/.git")
    message(FATAL_ERROR "Existing directory was initialized as a repository")
endif()

set(checkout "${root}/third_party/concurrent")
set(repository "${root}/origin")
set(TP1_NATIVE_OFFLINE OFF)
set(arguments "-DTEST_CHECKOUT=${checkout}" "-DTEST_REPOSITORY=${repository}"
    "-DTEST_REVISION=${revision}" -P "${CMAKE_CURRENT_LIST_FILE}")
# Commands in execute_process run concurrently; both must share one checkout safely.
execute_process(COMMAND "${CMAKE_COMMAND}" ${arguments} COMMAND "${CMAKE_COMMAND}" ${arguments}
    RESULTS_VARIABLE results OUTPUT_VARIABLE output ERROR_VARIABLE error)
if(NOT results STREQUAL "0;0")
    message(FATAL_ERROR "Concurrent checkout failed: ${results}\n${output}\n${error}")
endif()
message(STATUS "PASS: concurrent downloads share one checkout")

file(REMOVE_RECURSE "${root}")
message(STATUS "All native dependency tests passed")
