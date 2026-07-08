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

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MyGLSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private boolean mPaused = false;

    public MyGLSurfaceView(Context context) {
        this(context, null);
    }

    public MyGLSurfaceView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        init();
    }

    private void init() {
        getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        MainActivity.setRenderSurface(holder.getSurface());
        MainActivity.setRenderPaused(mPaused);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        MainActivity.setRenderSize(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        MainActivity.releaseRenderSurface();
    }

    public void onPause() {
        mPaused = true;
        MainActivity.setRenderPaused(true);
    }

    public void onResume() {
        mPaused = false;
        MainActivity.setRenderPaused(false);
    }

    public SurfaceTexture getSurfaceTexture() {
        return null;
    }
}

class RenderThread extends Thread implements SurfaceTexture.OnFrameAvailableListener {

    RenderThread(Surface surface) {
        mSurface = surface;
        mVertices = ByteBuffer.allocateDirect(mVerticesData.length
                * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVertices.put(mVerticesData).position(0);
        Matrix.setIdentityM(mSTMatrix, 0);
    }

    @Override
    public void run() {
        try {
            initEgl();
            initGl();
            drawFrame();
            EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
            renderLoop();
        } finally {
            releaseGl();
            releaseEgl();
        }
    }

    void setViewSize(int width, int height) {
        synchronized (mLock) {
            mViewWidth = width;
            mViewHeight = height;
            mSizeChanged = true;
            mLock.notifyAll();
        }
    }

    void onPause() {
        synchronized (mLock) {
            mPaused = true;
        }
    }

    void onResume() {
        synchronized (mLock) {
            mPaused = false;
            mLock.notifyAll();
        }
    }

    void shutdown() {
        synchronized (mLock) {
            mRunning = false;
            mLock.notifyAll();
        }
        try {
            join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    SurfaceTexture getSurfaceTexture() {
        synchronized (mLock) {
            long endTime = SystemClock.uptimeMillis() + 3000;
            while (mRunning && mSurfaceTexture == null) {
                long waitTime = endTime - SystemClock.uptimeMillis();
                if (waitTime <= 0) {
                    break;
                }
                try {
                    mLock.wait(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return mSurfaceTexture;
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (mLock) {
            mFrameAvailable = true;
            mLock.notifyAll();
        }
    }

    private void renderLoop() {
        while (true) {
            boolean updateFrame = false;
            synchronized (mLock) {
                while (mRunning && (mPaused || (!mFrameAvailable && !mSizeChanged))) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        mRunning = false;
                    }
                }
                if (!mRunning) {
                    break;
                }
                updateFrame = mFrameAvailable;
                mFrameAvailable = false;
            }

            if (updateFrame) {
                mSurfaceTexture.updateTexImage();
                mSurfaceTexture.getTransformMatrix(mSTMatrix);
            }

            drawFrame();
            EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
        }
    }

    private void initEgl() {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0,
                configs.length, numConfigs, 0)) {
            throw new RuntimeException("eglChooseConfig failed");
        }

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        checkEglError("eglCreateContext");

        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0], mSurface,
                surfaceAttribs, 0);
        checkEglError("eglCreateWindowSurface");

        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private void releaseEgl() {
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (mEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
                mEglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (mEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEglDisplay, mEglContext);
                mEglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(mEglDisplay);
            mEglDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private void initGl() {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

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

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);
        checkGlError("glBindTexture mTextureID");

        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri mTextureID");

        mSurfaceTexture = new SurfaceTexture(mTextureID);
        mSurfaceTexture.setOnFrameAvailableListener(this);

        Matrix.setLookAtM(mVMatrix, 0, 0, 0, 4f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
        Matrix.setIdentityM(mProjMatrix, 0);
        Matrix.setIdentityM(mMMatrix, 0);

        mLastTime = SystemClock.elapsedRealtimeNanos();

        synchronized (mLock) {
            mFrameAvailable = false;
            mLock.notifyAll();
        }
    }

    private void releaseGl() {
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mTextureID != 0) {
            int[] textures = {mTextureID};
            GLES20.glDeleteTextures(1, textures, 0);
            mTextureID = 0;
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }

    private void drawFrame() {
        synchronized (mLock) {
            if (mSizeChanged) {
                GLES20.glViewport(0, 0, mViewWidth, mViewHeight);
                Matrix.setIdentityM(mProjMatrix, 0);
                Matrix.setIdentityM(mVMatrix, 0);
                mSizeChanged = false;
            }
        }

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);

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

        long now = SystemClock.elapsedRealtimeNanos();
        mRunTime += (now - mLastTime);
        mLastTime = now;

        Matrix.setIdentityM(mMMatrix, 0);

        Matrix.multiplyMM(mMVPMatrix, 0, mVMatrix, 0, mMMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mMVPMatrix, 0);

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");

        //Log.i("@@@", "TYY drawFrame");
    }

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

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

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

    private void checkEglError(String op) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(op + ": eglError 0x" + Integer.toHexString(error));
        }
    }

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int VERTICES_DATA_POS_OFFSET = 0;
    private static final int VERTICES_DATA_UV_OFFSET = 3;
    private final float[] mVerticesData = {
            -1f, -1f, 0, 0.f, 0.f,
            1f, -1f, 0, 1.f, 0.f,
            -1f, 1f, 0, 0.f, 1.f,
            1f, 1f, 0, 1.f, 1.f,
    };

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

    private final Object mLock = new Object();
    private final Surface mSurface;
    private FloatBuffer mVertices;

    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;

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

    private SurfaceTexture mSurfaceTexture;
    private boolean mRunning = true;
    private boolean mPaused = false;
    private boolean mFrameAvailable = false;
    private boolean mSizeChanged = true;
    private int mViewWidth = 1;
    private int mViewHeight = 1;
    private long mLastTime = -1;
    private long mRunTime = 0;

    private static final String TAG = "RenderThread";
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
}
