cmake_minimum_required(VERSION 3.18.1)
project("androidaimanager")

# Отключаем нативную сборку, так как мы собираем на ПК для телефона (кросс-компиляция)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Явно задаем базовую архитектуру ARMv8, чтобы не было ошибки -mcpu=native
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)

# Отключаем OpenMP (часто вызывает проблемы при кросс-компиляции в Android)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Отключаем Vulkan/Metal/OpenCL
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(native-lib SHARED native-lib.cpp)

target_link_libraries(native-lib
        llama
        log)