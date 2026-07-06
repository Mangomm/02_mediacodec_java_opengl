cmake_minimum_required(VERSION 3.4.1)

project(NativeCodec)

add_library(native-codec-jni SHARED
        looper.cpp
        native-codec-jni.cpp
)

target_link_libraries(native-codec-jni
        PRIVATE
        android
        log
        mediandk
        OpenMAXAL
)