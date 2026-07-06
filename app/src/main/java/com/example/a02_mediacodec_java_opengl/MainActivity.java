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

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.RadioButton;
import android.widget.Spinner;

public class MainActivity extends Activity {
    static final String TAG = "NativeCodec";

    String mSourceString = null;

    // 正常SurfaceView和SurfaceHolder
    SurfaceView mSurfaceView1;
    SurfaceHolder mSurfaceHolder1;

    VideoSink mSelectedVideoSink;
    VideoSink mNativeCodecPlayerVideoSink;

    SurfaceHolderVideoSink mSurfaceHolder1VideoSink;
    GLViewVideoSink mGLView1VideoSink;

    boolean mCreated = false;
    boolean mIsPlaying = false;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        // 加载 UI + View 树, 后面所有 findViewById 都是从这棵树里找对象
        setContentView(R.layout.activity_main);

        // OpenGL 渲染通道, 用途：给 SurfaceTexture 用（MediaCodec → GL → Screen）
        mGLView1 = (MyGLSurfaceView) findViewById(R.id.glsurfaceview1);

        // 正常SurfaceView（无 GL）
        // set up the Surface 1 video sink
        mSurfaceView1 = (SurfaceView) findViewById(R.id.surfaceview1);
        mSurfaceHolder1 = mSurfaceView1.getHolder();

        mSurfaceHolder1.addCallback(new SurfaceHolder.Callback() {

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.v(TAG, "surfaceChanged format=" + format + ", width=" + width + ", height="
                        + height);
            }

            // Surface 不是一开始就有的
            //必须等 surfaceCreated
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.v(TAG, "surfaceCreated");
                if (mRadio1.isChecked()) {
                    // 调用jni接口, 传递给jni, holder.getSurface() ≈ ANativeWindow*
                    setSurface(holder.getSurface());
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.v(TAG, "surfaceDestroyed");
            }

        });

        // Spinner：选择播放源（数据源）, 就是选择哪个播放文件
        // initialize content source spinner
        Spinner sourceSpinner = (Spinner) findViewById(R.id.source_spinner1);
        ArrayAdapter<CharSequence> sourceAdapter = ArrayAdapter.createFromResource(
                this, R.array.source_array, android.R.layout.simple_spinner_item);
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(sourceAdapter);
        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            // 更新用户选择的播放文件路径
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                mSourceString = parent.getItemAtPosition(pos).toString();
                Log.v(TAG, "onItemSelected " + mSourceString);
            }

            @Override
            public void onNothingSelected(AdapterView parent) {
                Log.v(TAG, "onNothingSelected");
                mSourceString = null;
            }

        });

        // 选择“视频输出目标”
        mRadio1 = (RadioButton) findViewById(R.id.radio1);
        mRadio2 = (RadioButton) findViewById(R.id.radio2);

        OnCheckedChangeListener checklistener = new CompoundButton.OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Log.i("@@@@", "oncheckedchanged");
                // 选择了mRadio1,  将mRadio2置为未选择
                if (buttonView == mRadio1 && isChecked) {
                    mRadio2.setChecked(false);
                }
                // 选择了mRadio2, 将mRadio1置为未选择
                if (buttonView == mRadio2 && isChecked) {
                    mRadio1.setChecked(false);
                }
                if (isChecked) {
                    if (mRadio1.isChecked()) {
                        // 正常surface sink:
                        // 创建一个 SurfaceHolderVideoSink
                        //告诉系统：
                        //“视频帧以后直接往 SurfaceView 输出”
                        if (mSurfaceHolder1VideoSink == null) {
                            // 这里看到，继承VideoSink，是为了方便用mSelectedVideoSink基类指针管理
                            mSurfaceHolder1VideoSink = new SurfaceHolderVideoSink(mSurfaceHolder1);
                        }
                        mSelectedVideoSink = mSurfaceHolder1VideoSink;
                        mGLView1.onPause();
                        Log.i("@@@@", "glview pause");
                    } else {
                        mGLView1.onResume();
                        if (mGLView1VideoSink == null) {
                            mGLView1VideoSink = new GLViewVideoSink(mGLView1);
                        }
                        mSelectedVideoSink = mGLView1VideoSink;
                    }
                    // 更新表面
                    switchSurface();
                }
            }
        };
        // 设置回调函数
        mRadio1.setOnCheckedChangeListener(checklistener);
        mRadio2.setOnCheckedChangeListener(checklistener);
        // 默认选中mRadio2
        mRadio2.toggle();

        // 这个支持使用鼠标或者触屏的手，点击哪个视图时，更新mRadio1选择和mRadio2
        // the surfaces themselves are easier targets than the radio buttons
        mSurfaceView1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRadio1.toggle();
            }
        });
        mGLView1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRadio2.toggle();
            }
        });

        // initialize button click handlers

        // native MediaPlayer start/pause
        ((Button) findViewById(R.id.start_native)).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                if (!mCreated) {
                    // 更新mNativeCodecPlayerVideoSink
                    if (mNativeCodecPlayerVideoSink == null) {
                        if (mSelectedVideoSink == null) {
                            return;
                        }
                        mSelectedVideoSink.useAsSinkForNative();
                        mNativeCodecPlayerVideoSink = mSelectedVideoSink;
                    }
                    // 创建播放器
                    if (mSourceString != null) {
                        mCreated = createStreamingMediaPlayer(getResources().getAssets(),
                                mSourceString);
                    }
                }
                // 创建成功
                if (mCreated) {
                    mIsPlaying = !mIsPlaying;
                    // 更新jni播放器状态，进行解码
                    setPlayingStreamingMediaPlayer(mIsPlaying);
                }
            }

        });


        // native MediaPlayer rewind
        ((Button) findViewById(R.id.rewind_native)).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                if (mNativeCodecPlayerVideoSink != null) {
                    // 推送kMsgSeek事件, 回到播放开始.
                    rewindStreamingMediaPlayer();
                }
            }

        });
    }

    // 把正在播放的视频，从“旧 Surface”切到“新 Surface”
    void switchSurface() {
        if (mCreated && mNativeCodecPlayerVideoSink != mSelectedVideoSink) {
            // shutdown and recreate on other surface
            Log.i("@@@", "shutting down player");
            // 调用jni接口
            shutdown();
            mCreated = false;
            // 更新surface, 正常surface <-> opengl surface都有可能
            mSelectedVideoSink.useAsSinkForNative();
            mNativeCodecPlayerVideoSink = mSelectedVideoSink;
            if (mSourceString != null) {
                Log.i("@@@", "recreating player");
                mCreated = createStreamingMediaPlayer(getResources().getAssets(),mSourceString);
                mIsPlaying = false;
            }
        }
    }

    /** Called when the activity is about to be paused. */
    @Override
    protected void onPause()
    {
        mIsPlaying = false;
        setPlayingStreamingMediaPlayer(false);
        mGLView1.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mRadio2.isChecked()) {
            mGLView1.onResume();
        }
    }

    /** Called when the activity is about to be destroyed. */
    @Override
    protected void onDestroy()
    {
        shutdown();
        mCreated = false;
        super.onDestroy();
    }

    // 自定义opengl表面视图
    private MyGLSurfaceView mGLView1;

    // 选择了正常surface
    private RadioButton mRadio1;

    // 选择openl surface
    private RadioButton mRadio2;

    /** Native methods, implemented in jni folder */
//    public static native void createEngine();
    public static native boolean createStreamingMediaPlayer(AssetManager assetMgr, String filename);
    public static native void setPlayingStreamingMediaPlayer(boolean isPlaying);
    public static native void shutdown();
    public static native void setSurface(Surface surface);
    public static native void rewindStreamingMediaPlayer();

    /** Load jni .so on initialization */
    static {
        System.loadLibrary("native-codec-jni");
    }

    // VideoSink abstracts out the difference between Surface and SurfaceTexture
    // aka SurfaceHolder and GLSurfaceView
    static abstract class VideoSink {

        abstract void setFixedSize(int width, int height);
        abstract void useAsSinkForNative();

    }

    // 正常surface sink
    static class SurfaceHolderVideoSink extends VideoSink {

        private final SurfaceHolder mSurfaceHolder;

        SurfaceHolderVideoSink(SurfaceHolder surfaceHolder) {
            mSurfaceHolder = surfaceHolder;
        }

        @Override
        void setFixedSize(int width, int height) {
            mSurfaceHolder.setFixedSize(width, height);
        }

        @Override
        void useAsSinkForNative() {
            Surface s = mSurfaceHolder.getSurface();
            Log.i("@@@", "setting surface " + s);
            setSurface(s);
        }

    }

    // opengl sink
    static class GLViewVideoSink extends VideoSink {

        // 自定义的opengl输出视图
        private final MyGLSurfaceView mMyGLSurfaceView;

        GLViewVideoSink(MyGLSurfaceView myGLSurfaceView) {
            mMyGLSurfaceView = myGLSurfaceView;
        }

        @Override
        void setFixedSize(int width, int height) {
        }

        @Override
        void useAsSinkForNative() {
            SurfaceTexture st = mMyGLSurfaceView.getSurfaceTexture();
            Surface s = new Surface(st);
            setSurface(s);
            s.release();
        }

    }

}
