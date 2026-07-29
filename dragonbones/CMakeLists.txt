cmake_minimum_required(VERSION 3.22.1)
project("dragonbones_native")

# === OPERIT BUILD - Dragonbones Module ===
# FIX NDK 27: C++17 + aligned new/delete

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -faligned-new -fexceptions -frtti")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fexceptions")

file(GLOB_RECURSE DRAGONBONES_SOURCES
    "cpp/dragonBones/animation/*.cpp"
    "cpp/dragonBones/armature/*.cpp"
    "cpp/dragonBones/core/*.cpp"
    "cpp/dragonBones/event/*.cpp"
    "cpp/dragonBones/factory/*.cpp"
    "cpp/dragonBones/geom/*.cpp"
    "cpp/dragonBones/model/*.cpp"
    "cpp/dragonBones/parser/*.cpp"
    "cpp/opengl/OpenGLSlot.cpp"
    "cpp/opengl/OpenGLFactory.cpp"
)

add_library(
    dragonbones_native
    SHARED
    "cpp/JniBridge.cpp"
    ${DRAGONBONES_SOURCES}
)

target_include_directories(dragonbones_native PRIVATE
    "cpp"
    "cpp/thirdParty/stb"
)

# FIX: Link ONLY android libs, c++_shared vine automat de la Gradle
# NU MAI LINKUI c++abi explicit!
target_link_libraries(dragonbones_native PRIVATE
    android
    log
    GLESv2
    c++_shared
)

# 16KB page size support (Android 15+)
target_link_options(dragonbones_native PRIVATE "-Wl,-z,max-page-size=16384")
