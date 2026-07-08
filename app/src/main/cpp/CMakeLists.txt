cmake_minimum_required(VERSION 3.4.1)

project(NativeCodec)

add_library(native-codec-jni SHARED
        NativeRenderThread.cpp
        looper.cpp
        native-codec-jni.cpp
)

target_link_libraries(native-codec-jni
        PRIVATE
        android
        EGL
        GLESv2
        log
        mediandk
        OpenMAXAL
)
