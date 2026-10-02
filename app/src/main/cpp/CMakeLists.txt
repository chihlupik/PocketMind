cmake_minimum_required(VERSION 3.22.1)
project("androidaimanager")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(
        native-lib
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib
        llama
        ${log-lib}
)
