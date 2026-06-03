cmake_minimum_required(VERSION 3.22.1)

project(rougo_hoshidicts_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/submodules/hoshidicts")
set(HOSHIDICTS_BRIDGE_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/submodules/hoshidicts-kotlin-bridge/app/src/main/cpp")

add_subdirectory("${HOSHIDICTS_CORE_DIR}" "${CMAKE_BINARY_DIR}/hoshidicts-core")

add_library(hoshidicts_jni SHARED
    "${HOSHIDICTS_BRIDGE_CPP_DIR}/hoshidicts_jni.cpp"
    src/main/cpp/rougo_hoshi_jni.cpp
)

find_library(log-lib log)

target_include_directories(hoshidicts_jni PRIVATE
    "${HOSHIDICTS_CORE_DIR}/include"
)

target_link_libraries(hoshidicts_jni PRIVATE
    hoshidicts
    ${log-lib}
)
