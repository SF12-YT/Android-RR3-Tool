cmake_minimum_required(VERSION 3.22.1)
project(AndroidRR3Tool LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MT_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/Monkey-Tool/src")
if(NOT EXISTS "${MT_SRC}/nfsnl.h")
    message(FATAL_ERROR "Monkey Tool submodule is missing. Clone with --recurse-submodules.")
endif()

file(GLOB LZHAM_SOURCES CONFIGURE_DEPENDS "${MT_SRC}/lzham/*.cpp")
file(GLOB VORBIS_SOURCES CONFIGURE_DEPENDS "${MT_SRC}/thirdparty/vorbis/lib/*.c")

set(THIRDPARTY_SOURCES
    "${MT_SRC}/thirdparty/bc7enc/bc7enc.c"
    "${MT_SRC}/thirdparty/miniz/miniz.c"
    "${MT_SRC}/thirdparty/miniz/miniz_tdef.c"
    "${MT_SRC}/thirdparty/miniz/miniz_tinfl.c"
    "${MT_SRC}/thirdparty/ogg/bitwise.c"
    "${MT_SRC}/thirdparty/ogg/framing.c"
    ${VORBIS_SOURCES}
)

set(MONKEY_TOOL_CORE_SOURCES
    "${MT_SRC}/nfsnl_core.cpp"
    "${MT_SRC}/nfsnl_image.cpp"
    "${MT_SRC}/nfsnl_model.cpp"
    "${MT_SRC}/nfsnl_m3g.cpp"
    "${MT_SRC}/nfsnl_render.cpp"
    "${MT_SRC}/nfsnl_audio.cpp"
    "${MT_SRC}/nfsnl_text.cpp"
    "${MT_SRC}/nfsnl_library.cpp"
    "${MT_SRC}/nfsnl_profiles.cpp"
    "${MT_SRC}/nfsnl_rr3.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/car_editor/nfsnl_rr3car.cpp"
    "${MT_SRC}/nfsnl_rr3add.cpp"
    "${MT_SRC}/nfsnl_rr3career.cpp"
    "${MT_SRC}/nfsnl_gltf.cpp"
    "${MT_SRC}/nfsnl_export.cpp"
    "${MT_SRC}/nfsnl_import.cpp"
    "${MT_SRC}/nfsnl_data.cpp"
    "${MT_SRC}/nfsnl_save.cpp"
    "${MT_SRC}/nfsnl_sbin.cpp"
    "${MT_SRC}/nfsnl_decode.cpp"
    "${MT_SRC}/nfsnl_encode.cpp"
    "${MT_SRC}/nfsnl_im2.cpp"
    "${MT_SRC}/nfsnl_scene.cpp"
    "${MT_SRC}/nfsnl_astc.cpp"
    "${MT_SRC}/nfsnl_brotli.cpp"
    "${MT_SRC}/nfsnl_lzma.cpp"
    "${MT_SRC}/nfsnl_jsr.cpp"
    "${MT_SRC}/nfsnl_lzham.cpp"
)

add_library(monkeytool_android SHARED
    native_bridge.cpp
    ${MONKEY_TOOL_CORE_SOURCES}
    ${LZHAM_SOURCES}
    ${THIRDPARTY_SOURCES}
)

target_include_directories(monkeytool_android PRIVATE
    "${MT_SRC}"
    "${MT_SRC}/thirdparty/miniz"
    "${MT_SRC}/thirdparty/ogg"
    "${MT_SRC}/thirdparty/vorbis"
    "${MT_SRC}/thirdparty/vorbis/lib"
)

target_compile_definitions(monkeytool_android PRIVATE
    MINIZ_NO_STDIO
    MINIZ_NO_TIME
    MINIZ_NO_ARCHIVE_APIS
    MINIZ_NO_ZLIB_COMPATIBLE_NAMES
)

set_source_files_properties(
    ${LZHAM_SOURCES}
    ${THIRDPARTY_SOURCES}
    PROPERTIES COMPILE_OPTIONS "-w"
)

find_library(ANDROID_LOG_LIB log)
find_library(ANDROID_GLESV3_LIB GLESv3)
target_link_libraries(monkeytool_android PRIVATE ${ANDROID_LOG_LIB} ${ANDROID_GLESV3_LIB} ${CMAKE_DL_LIBS})
