cmake_minimum_required(VERSION 3.22.1)
project(anvil_native)

# ARM64 only. The flags below match the CI XMRig flags for the
# Dimensity 7050 target; they are NOT a generic arm64-v8a floor.
add_library(anvil_native SHARED
    anvil_native.cpp
    blackhole_bench.cpp
    vulkan_miner.cpp
)

target_compile_options(anvil_native PRIVATE
    -O3
    -march=armv8.2-a+crypto+aes+sha2+dotprod+fp16
    -mtune=cortex-a78
    -flto
    -funroll-loops
    -fomit-frame-pointer
)

find_library(log-lib log)
find_library(vulkan-lib vulkan)
find_library(android-lib android)

target_link_libraries(anvil_native
    ${log-lib}
    ${vulkan-lib}
    ${android-lib}
)
