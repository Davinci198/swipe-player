cmake_minimum_required(VERSION 3.22.1)
project("dragonbones_native")

# FIX NDK 27: C++17 + aligned new/delete
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -faligned-new -faligned-allocation -fexceptions -frtti")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -faligned-allocation")

# DragonBones sources
file(GLOB_RECURSE DRAGONBONES_SOURCES
    "dragonBones/animation/*.cpp"
    "dragonBones/armature/*.cpp"
    "dragonBones/core/*.cpp"
    "dragonBones/event/*.cpp"
    "dragonBones/factories/*.cpp"
    "dragonBones/geom/*.cpp"
    "dragonBones/model/*.cpp"
    "dragonBones/textures/*.cpp"
    "dragonBones/utils/*.cpp"
)

# JNI bridge
file(GLOB JNI_SOURCES
    "jni/*.cpp"
)

add_library(
    dragonbones_native
    SHARED
    ${DRAGONBONES_SOURCES}
    ${JNI_SOURCES}
)

find_library(log-lib log)
find_library(GLESv2-lib GLESv2)
find_library(android-lib android)
find_library(CPP_SHARED c++_shared)
find_library(CPP_ABI c++abi)

target_include_directories(dragonbones_native PRIVATE
    dragonBones
    .
)

if(CPP_SHARED)
    target_link_libraries(dragonbones_native PUBLIC
        ${log-lib}
        ${GLESv2-lib}
        ${android-lib}
        ${CPP_SHARED}
    )
    if(CPP_ABI)
        target_link_libraries(dragonbones_native PUBLIC ${CPP_ABI})
    endif()
else()
    target_link_libraries(dragonbones_native PUBLIC
        ${log-lib}
        ${GLESv2-lib}
        ${android-lib}
        c++_shared
    )
endif()

# 16KB page size support (Android 15+)
target_link_options(dragonbones_native PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(dragonbones_native PRIVATE "-Wl,--no-undefined")
target_compile_options(dragonbones_native PRIVATE -faligned-new -std=c++17)