cmake_minimum_required(VERSION 3.22.1)
project(dictus_trie LANGUAGES CXX)

# NDK r27's flexible-page-size option sets max-page-size; align RELRO too.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    add_link_options("-Wl,-z,common-page-size=16384")
endif()

add_library(
    dictus_trie
    SHARED
    src/dictus_trie.cpp
    src/dictus_scorer.cpp
    src/dictus_proximity.cpp
    src/dictus_ngram.cpp
    dictus_trie_jni.cpp
)

target_include_directories(dictus_trie PRIVATE include)
target_compile_features(dictus_trie PRIVATE cxx_std_17)
target_compile_options(dictus_trie PRIVATE -O3 -Wall -Wextra -Werror)
