cmake_minimum_required(VERSION 3.13)

project(HboxNitroDocx)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
  HboxNitroDocx
  SHARED
  src/main/cpp/cpp-adapter.cpp
)

include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/HboxNitroDocx+autolinking.cmake)
