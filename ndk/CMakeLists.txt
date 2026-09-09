cmake_minimum_required(VERSION 3.10)
set(CMAKE_EXPORT_COMPILE_COMMANDS 1)
project(fudge)
add_link_options("-Wl,-z,max-page-size=16384")

option(APP_WASM_SUPPORT "" ON)
option(APP_QUICKJS_SUPPORT "" ON)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2")
endif()

# wasm runtime
if(APP_WASM_SUPPORT)
    set(WAMR_BUILD_SIMD "" OFF)
    set(WAMR_BUILD_PLATFORM "android")
    if(ANDROID_ABI STREQUAL "x86_64")
        set(WAMR_BUILD_TARGET "X86_64")
    elseif(ANDROID_ABI STREQUAL "arm64-v8a")
        set(WAMR_BUILD_TARGET "AARCH64")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(WAMR_BUILD_TARGET "ARMV7A")
    endif()
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/wasm-micro-runtime/ ${CMAKE_CURRENT_BINARY_DIR}/wamr)
    target_link_options(vmlib PRIVATE "-Wl,-z,max-page-size=16384")
endif()

# libjpeg-turbo
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libjpeg-turbo ${CMAKE_CURRENT_BINARY_DIR}/libjpeg)
target_link_options(libjpeg-turbo PRIVATE "-Wl,-z,max-page-size=16384")

# quickjs
if(APP_QUICKJS_SUPPORT)
    option(QJS_BUILD_LIBC "Build standard library modules as part of the library" ON)
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/quickjs ${CMAKE_CURRENT_BINARY_DIR}/quickjs)
    target_link_options(qjs PRIVATE "-Wl,-z,max-page-size=16384")
endif()

# libpak
option(PAK_INCLUDE_CLI "cli program" OFF)
option(PAK_INCLUDE_WIFI "WiFi APIs" ON)
option(PAK_INCLUDE_BT "Bluetooth APIs" ON)
option(PAK_INCLUDE_EXIF "EXIF Parser" ON)
option(PAK_QUICKJS "QuickJS bindings" ${APP_QUICKJS_SUPPORT})
option(PAK_WASM "" ${APP_WASM_SUPPORT})
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/../../pak)
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../pak ${CMAKE_CURRENT_BINARY_DIR}/libpak)
else()
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../libpak ${CMAKE_CURRENT_BINARY_DIR}/libpak)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
    main.c
    thread.c
    module.c
    liveview.c
    exif.c
)
target_compile_options(${CMAKE_PROJECT_NAME}
    PRIVATE -Wall -Wshadow -Wcast-qual -Wpedantic -Werror=incompatible-pointer-types -Werror=incompatible-pointer-types -Werror=deprecated-declarations -Werror=narrowing
)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE -Wl,--whole-archive pak -Wl,--no-whole-archive libjpeg-turbo)
get_target_property(PAK_INCLUDES_TEMP pak INCLUDE_DIRECTORIES)
target_include_directories(${CMAKE_PROJECT_NAME} PUBLIC ${PAK_INCLUDES_TEMP})
if(APP_WASM_SUPPORT)
    target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE vmlib)
endif()
if(APP_QUICKJS_SUPPORT)
    target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE qjs)
endif()

# pakrt alias for shared library modules
add_library(pakrt INTERFACE)
#get_target_property(MY_INCLUDES pak INCLUDE_DIRECTORIES)
target_link_libraries(pakrt INTERFACE ${CMAKE_PROJECT_NAME})
#target_include_directories(pakrt INTERFACE ${MY_INCLUDES})

# compile modules so they can be opened with dlopen

# libfuji static module
option(LIBFUJI_SHARED "Build using shared libraries" ON)
option(LIBFUJI_MODULE "Compile as libpak module" ON)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../modules/libfuji ${CMAKE_CURRENT_BINARY_DIR}/libfuji)
# dummy static module
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../modules/dummy ${CMAKE_CURRENT_BINARY_DIR}/dummy)
# dummy cmf module
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../modules/nothing-buds ${CMAKE_CURRENT_BINARY_DIR}/nothing)
# dummy goveelife module
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../modules/goveelife ${CMAKE_CURRENT_BINARY_DIR}/goveelife)
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/../modules/ptp2)
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../modules/ptp2 ${CMAKE_CURRENT_BINARY_DIR}/ptp2)
endif()