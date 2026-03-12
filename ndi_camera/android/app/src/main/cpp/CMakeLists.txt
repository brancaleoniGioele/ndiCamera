cmake_minimum_required(VERSION 3.22.1)
project("ndi_sender")

add_library(
    ndi_sender
    SHARED
    ndi_sender.cpp
)

target_include_directories(ndi_sender PRIVATE include)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    ndi_sender
    ${log-lib}
    ${android-lib}
)