/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.a02_mediacodec_java_opengl;

import android.graphics.SurfaceTexture;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.AttributeSet;

public class MyGLSurfaceView extends GLSurfaceView {

    // 自定义渲染器
    MyRenderer mRenderer;

    public MyGLSurfaceView(Context context) {
        this(context, null);
    }

    public MyGLSurfaceView(Context context, AttributeSet attributeSet) {
        // super: 调用父类 GLSurfaceView 的构造函数，用来正确初始化父类那一整套内部状态。
        // 类似C++:
        // class MyGLSurfaceView : public GLSurfaceView {
        // public:
        //    MyGLSurfaceView(Context* context, AttributeSet* attrs)
        //        : GLSurfaceView(context, attrs)   // 👈 这就是 super(...)
        //    {
        //        init();
        //    }
        // };
        super(context, attributeSet);
        init();
    }

    private void init() {
        // 告诉系统：我要用 OpenGL ES 2.0（Shader 管线）
        // 在 C++ / EGL 里，大概等价于：
        // EGLint attribs[] = {
        //    EGL_CONTEXT_CLIENT_VERSION, 2,
        //    EGL_NONE
        //};
        //eglCreateContext(display, config, EGL_NO_CONTEXT, attribs);
        setEGLContextClientVersion(2);
        mRenderer = new MyRenderer();
        // 它真正启动了整个 OpenGL 子系统
        //调用这行之后，Android 会：
        //创建一个 独立的 GLThread
        //创建 EGLDisplay / EGLContext / EGLSurface
        //在 GLThread 中回调：
        //onSurfaceCreated()
        //onSurfaceChanged()
        //不断调用 onDrawFrame()
        //
        // C++类似：
        // 伪代码：
        // create_render_thread();
        // create_egl_context();
        // while (running) {
        //     renderer->onDrawFrame();
        // }
        setRenderer(mRenderer);
        Log.i("@@@", "setrenderer");
    }

    // 暂停/恢复生命周期通知 (onPause() / onResume())
    @Override
    public void onPause() {
        // onPause: 先通知“你自己的逻辑”，再让系统停 GLThread
        mRenderer.onPause();
        super.onPause();// 暂停 / 停止 GLThread
    }

    @Override
    public void onResume() {
        // 先开启系统的工作, 再开启自己的工作.
        super.onResume();
        mRenderer.onResume();
    }

    public SurfaceTexture getSurfaceTexture() {
        return mRenderer.getSurfaceTexture();
    }
}

class MyRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public MyRenderer() {
        // mVerticesData 是四个顶点（X, Y, Z, U, V），相当于 C++ 的 VBO 数据,
        // float vertices[] = {
        //    -1.25f, -1.0f, 0, 0.f, 0.f,
        //     1.25f, -1.0f, 0, 1.f, 0.f,
        //    -1.25f,  1.0f, 0, 0.f, 1.f,
        //     1.25f,  1.0f, 0, 1.f, 1.f,
        //};
        // FloatBuffer 是 Java 中的 直接缓冲区，保证和底层 OpenGL ES 内存对齐。
        // mSTMatrix 是 SurfaceTexture 的变换矩阵，类似 C++ 里你拿到 外部纹理的 transform 矩阵。
        mVertices = ByteBuffer.allocateDirect(mVerticesData.length
                * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVertices.put(mVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);
    }
    public void onPause() {
    }

    public void onResume() {
        mLastTime = SystemClock.elapsedRealtimeNanos();
    }

    // 每帧渲染逻辑, 核心渲染循环
    @Override
    public void onDrawFrame(GL10 glUnused) {
        // 一 如果有新帧：
        // 调用 updateTexImage() 更新 GPU 上的纹理
        // 拿到 mSTMatrix → 纹理坐标变换
        //
        // 这里类似 C++ 里你每帧调用：
        // glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
        // eglUpdateTextureImage(...);
        synchronized(this) {
            if (updateSurface) {
                mSurface.updateTexImage();

                mSurface.getTransformMatrix(mSTMatrix);
                updateSurface = false;
            }
        }

        // 二：接着是 OpenGL 绘制：

        // 2.1和2.2 类似 C++：
        // glUseProgram(program);
        // glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
        // glVertexAttribPointer(posLoc, 3, GL_FLOAT, false, stride, vertices);
        // glEnableVertexAttribArray(posLoc);

        // 2.1 绑定 program 和纹理
        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.
        GLES20.glClear( GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);
        // 2.2 设置顶点属性
        mVertices.position(VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                VERTICES_DATA_STRIDE_BYTES, mVertices);
        checkGlError("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGlError("glEnableVertexAttribArray maPositionHandle");

        mVertices.position(VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandle, 3, GLES20.GL_FLOAT, false,
                VERTICES_DATA_STRIDE_BYTES, mVertices);
        checkGlError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        checkGlError("glEnableVertexAttribArray maTextureHandle");

        // 三：矩阵计算：
        // 构造模型旋转矩阵 + 视图矩阵 + 投影矩阵
        long now = SystemClock.elapsedRealtimeNanos();
        mRunTime += (now - mLastTime);
        mLastTime = now;
        double d = ((double)mRunTime) / 1000000000;

//        Matrix.setIdentityM(mMMatrix, 0);
//        Matrix.rotateM(mMMatrix, 0, 30, (float)Math.sin(d), (float)Math.cos(d), 0);
        Matrix.setIdentityM(mMMatrix, 0);


        Matrix.multiplyMM(mMVPMatrix, 0, mVMatrix, 0, mMMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mMVPMatrix, 0);

        // mSTMatrix 用于纹理 UV 坐标变换（SurfaceTexture 的必要步骤）
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

        // 绘制一个矩形（4 顶点，strip 方式）
        // 对应 C++ 的：
        // glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");
    }

    // View 尺寸或投影矩阵更新
    @Override
    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.
//        GLES20.glViewport(0, 0, width, height);
//        mRatio = (float) width / height;
//        Matrix.frustumM(mProjMatrix, 0, -mRatio, mRatio, -1, 1, 3, 7);

        GLES20.glViewport(0, 0, width, height);
        // 直接单位矩阵（2D显示）
        Matrix.setIdentityM(mProjMatrix, 0);
        Matrix.setIdentityM(mVMatrix, 0);

    }

    // OpenGL 初始化
    @Override
    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.

        /* Set up alpha blending and an Android background color */
        // 开启 alpha blending
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        // 设置清屏颜色
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        // 一：Shader 编译与程序创建：
        // aPosition → 顶点坐标
        // aTextureCoord → UV 坐标
        // uMVPMatrix → 模型视图投影矩阵
        // uSTMatrix → SurfaceTexture 纹理变换矩阵
        //
        // 类似 C++:
        // GLuint program = glCreateProgram();
        // glAttachShader(program, vertexShader);
        // glAttachShader(program, fragmentShader);
        // glLinkProgram(program);
        // GLint posLoc = glGetAttribLocation(program, "aPosition");
        // GLint aTextureCoord = glGetAttribLocation(program, "aTextureCoord");
        // GLint uMVPMatrix = glGetUniformLocation(program, "uMVPMatrix");
        // GLint glGetUniformLocation = glGetUniformLocation(program, "aTextureCoord");
        /* Set up shaders and handles to their variables */
        mProgram = createProgram(mVertexShader, mFragmentShader);
        if (mProgram == 0) {
            return;
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        checkGlError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMVPMatrix");
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        checkGlError("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uSTMatrix");
        }


        // 二：外部纹理绑定
        /*
         * Create our texture. This has to be done each time the
         * surface is created.
         */
        // GL_TEXTURE_EXTERNAL_OES 是 外部纹理扩展，用于 Camera/MediaCodec 输出
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);
        checkGlError("glBindTexture mTextureID");

        // 设置过滤器和 wrap
        // Can't do mipmapping with camera source
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        // Clamp to edge is the only option
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri mTextureID");

        /*
         * Create the SurfaceTexture that will feed this textureID, and pass it to the camera
         */
        // 将纹理与视频帧源绑定
        mSurface = new SurfaceTexture(mTextureID);
        // 当新帧到达时，会触发 onFrameAvailable()，标记 updateSurface = true
        mSurface.setOnFrameAvailableListener(this);

        Matrix.setLookAtM(mVMatrix, 0, 0, 0, 4f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);

        synchronized(this) {
            updateSurface = false;
        }
    }

    // SurfaceTexture 通知有新帧
    @Override
    synchronized public void onFrameAvailable(SurfaceTexture surface) {
        /* For simplicity, SurfaceTexture calls here when it has new
         * data available.  Call may come in from some random thread,
         * so let's be safe and use synchronize. No OpenGL calls can be done here.
         */
        updateSurface = true;
        //Log.v(TAG, "onFrameAvailable " + surface.getTimestamp());
    }

    // 加载shader
    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    // 创建program
    private int createProgram(String vertexSource, String fragmentSource) {
        // 1. 创建顶点shader和像素格式shader
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        // 2. 创建program
        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int VERTICES_DATA_POS_OFFSET = 0;
    private static final int VERTICES_DATA_UV_OFFSET = 3;
//    private final float[] mVerticesData = {
//            // X, Y, Z, U, V
//            -1.25f, -1.0f, 0, 0.f, 0.f,
//            1.25f, -1.0f, 0, 1.f, 0.f,
//            -1.25f,  1.0f, 0, 0.f, 1.f,
//            1.25f,  1.0f, 0, 1.f, 1.f,
//    };
    private final float[] mVerticesData = {
         -1f, -1f, 0, 0.f, 0.f,
         1f, -1f, 0, 1.f, 0.f,
         -1f,  1f, 0, 0.f, 1.f,
         1f,  1f, 0, 1.f, 1.f,
     };


    private FloatBuffer mVertices;

    private final String mVertexShader =
            "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uSTMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVPMatrix * aPosition;\n" +
                    "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                    "}\n";

    private final String mFragmentShader =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private float[] mMVPMatrix = new float[16];
    private float[] mProjMatrix = new float[16];
    private float[] mMMatrix = new float[16];
    private float[] mVMatrix = new float[16];
    private float[] mSTMatrix = new float[16];

    private int mProgram;
    private int mTextureID;
    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int maPositionHandle;
    private int maTextureHandle;

    private float mRatio = 1.0f;
    private SurfaceTexture mSurface;
    private boolean updateSurface = false;
    private long mLastTime = -1;
    private long mRunTime = 0;

    private static final String TAG = "MyRenderer";

    // Magic key
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    public SurfaceTexture getSurfaceTexture() {
        return mSurface;
    }
}
