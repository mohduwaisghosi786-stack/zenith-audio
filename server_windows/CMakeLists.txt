cmake_minimum_required(VERSION 3.20)
project(zenith-server-windows LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    include
    ../protocol
)

set(SOURCES
    src/main.cpp
    src/wasapi_capture.cpp
    src/opus_encoder.cpp
    src/network_server.cpp
)

find_package(Opus QUIET)

if(NOT Opus_FOUND)
    # Fallback to pkg-config or vcpkg
    find_package(PkgConfig QUIET)
    if(PkgConfig_FOUND)
        pkg_check_modules(OPUS QUIET opus)
    endif()
endif()

add_executable(zenith-server-win11 ${SOURCES})

target_include_directories(zenith-server-win11 PRIVATE
    include
    ../protocol
    ${OPUS_INCLUDE_DIRS}
)

target_link_libraries(zenith-server-win11 PRIVATE
    ole32
    avrt
    ws2_32
    iphlpapi
    ${OPUS_LIBRARIES}
    opus
)

message(STATUS "Configured zenith-server-win11 for Windows 11 WASAPI Loopback Capture")
