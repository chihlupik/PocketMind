cmake_minimum_required(VERSION 3.18.1)
project("androidaimanager")

# Флаги оптимизации под ARM64
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -funroll-loops -march=armv8-a+dotprod")

add_subdirectory(llama.cpp)

add_library(native-lib SHARED native-lib.cpp)

target_link_libraries(native-lib
        llama
        log)