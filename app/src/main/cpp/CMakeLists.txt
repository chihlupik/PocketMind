cmake_minimum_required(VERSION 3.18.1)
project("androidaimanager")

# NEON/dotprod только для arm64
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+dotprod")
endif()

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -funroll-loops")

add_subdirectory(llama.cpp)

add_library(native-lib SHARED native-lib.cpp)

target_link_libraries(native-lib
        llama
        log)