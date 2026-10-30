# OpenWnn dictionary search engine (Apache License 2.0)
# Copyright (C) OMRON SOFTWARE Co., Ltd. 2008-2012
# Vendored from AOSP platform/packages/inputmethods/OpenWnn for Pianotype.
# The Japanese dictionary data (dictionary/WnnJpnDic.c) is linked directly
# into this library and OpenWnnDictionaryImpl is created with a NULL
# dicLibPath, so no separate dlopen() of a dictionary .so is needed.

cmake_minimum_required(VERSION 3.22)
project(wnndict C)

add_library(wnndict SHARED
    OpenWnnDictionaryImplJni.c
    engine/ndapi.c
    engine/neapi.c
    engine/ndbdic.c
    engine/ndfdic.c
    engine/ndldic.c
    engine/ndrdic.c
    engine/necode.c
    engine/ndcommon.c
    engine/nj_str.c
    dictionary/WnnJpnDic.c
)

target_include_directories(wnndict PRIVATE . include)

target_compile_options(wnndict PRIVATE -O2)

target_link_libraries(wnndict dl)
