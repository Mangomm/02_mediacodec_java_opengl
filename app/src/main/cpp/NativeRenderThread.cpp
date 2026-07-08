#include "NativeRenderThread.h"

#include <android/log.h>
#include <unistd.h>

#define TAG "NativeRenderThread"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

static const float VERTICES[] = {
        -1.f, -1.f, 0.f, 0.f, 0.f,
        1.f, -1.f, 0.f, 1.f, 0.f,
        -1.f, 1.f, 0.f, 0.f, 1.f,
        1.f, 1.f, 0.f, 1.f, 1.f,
};

static const int FLOAT_SIZE_BYTES = 4;
static const int VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
static const int VERTICES_DATA_POS_OFFSET = 0;
static const int VERTICES_DATA_UV_OFFSET = 3;

static const char* VERTEX_SHADER =
        "uniform mat4 uMVPMatrix;\n"
        "uniform mat4 uSTMatrix;\n"
        "attribute vec4 aPosition;\n"
        "attribute vec4 aTextureCoord;\n"
        "varying vec2 vTextureCoord;\n"
        "void main() {\n"
        "  gl_Position = uMVPMatrix * aPosition;\n"
        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n"
        "}\n";

static const char* FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float;\n"
        "varying vec2 vTextureCoord;\n"
        "uniform samplerExternalOES sTexture;\n"
        "void main() {\n"
        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n"
        "}\n";

NativeRenderThread::NativeRenderThread(JavaVM* java_vm)
        : _java_vm(java_vm),
          _thread_started(false),
          _running(false),
          _paused(false),
          _surface_changed(false),
          _view_width(1),
          _view_height(1),
          _render_window(NULL),
          _codec_window(NULL),
          _egl_display(EGL_NO_DISPLAY),
          _egl_context(EGL_NO_CONTEXT),
          _egl_surface(EGL_NO_SURFACE),
          _program(0),
          _texture_id(0),
          _mvp_matrix_handle(-1),
          _st_matrix_handle(-1),
          _position_handle(-1),
          _texture_handle(-1),
          _surface_texture(NULL),
          _surface_texture_object(NULL),
          _codec_surface_object(NULL)
{
    pthread_mutex_init(&_mutex, NULL);
    pthread_cond_init(&_cond, NULL);
    make_identity(_mvp_matrix);
    make_identity(_st_matrix);
}

NativeRenderThread::~NativeRenderThread()
{
    release_render_window();
    pthread_mutex_destroy(&_mutex);
    pthread_cond_destroy(&_cond);
}

void NativeRenderThread::set_render_window(ANativeWindow* window)
{
    release_render_window();

    pthread_mutex_lock(&_mutex);
    _render_window = window;
    if (_render_window) {
        ANativeWindow_acquire(_render_window);
    }
    _running = true;
    _paused = false;
    _surface_changed = true;
    pthread_mutex_unlock(&_mutex);

    if (!_thread_started) {
        _thread_started = pthread_create(&_thread, NULL, thread_entry, this) == 0;
    }
    pthread_cond_signal(&_cond);
}

void NativeRenderThread::set_render_size(int width, int height)
{
    pthread_mutex_lock(&_mutex);
    _view_width = width > 0 ? width : 1;
    _view_height = height > 0 ? height : 1;
    _surface_changed = true;
    pthread_cond_signal(&_cond);
    pthread_mutex_unlock(&_mutex);
}

void NativeRenderThread::set_paused(bool paused)
{
    pthread_mutex_lock(&_mutex);
    _paused = paused;
    pthread_cond_signal(&_cond);
    pthread_mutex_unlock(&_mutex);
}

void NativeRenderThread::release_render_window()
{
    pthread_mutex_lock(&_mutex);
    bool need_join = _thread_started;
    _running = false;
    pthread_cond_signal(&_cond);
    pthread_mutex_unlock(&_mutex);

    if (need_join) {
        pthread_join(_thread, NULL);
    }

    pthread_mutex_lock(&_mutex);
    _thread_started = false;
    if (_render_window) {
        ANativeWindow_release(_render_window);
        _render_window = NULL;
    }
    pthread_mutex_unlock(&_mutex);
}

ANativeWindow* NativeRenderThread::get_codec_window()
{
    pthread_mutex_lock(&_mutex);
    while (_running && _codec_window == NULL) {
        pthread_cond_wait(&_cond, &_mutex);
    }
    ANativeWindow* window = _codec_window;
    pthread_mutex_unlock(&_mutex);
    return window;
}

void* NativeRenderThread::thread_entry(void* obj)
{
    reinterpret_cast<NativeRenderThread*>(obj)->thread_loop();
    return NULL;
}

void NativeRenderThread::thread_loop()
{
    JNIEnv* env = NULL;
    bool attached = false;
    if (_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (_java_vm->AttachCurrentThread(&env, NULL) != JNI_OK) {
            return;
        }
        attached = true;
    }

    if (!init_egl() || !init_gl(env)) {
        release_gl(env);
        release_egl();
        if (attached) {
            _java_vm->DetachCurrentThread();
        }
        return;
    }

    while (true) {
        pthread_mutex_lock(&_mutex);
        bool running = _running;
        bool paused = _paused;
        bool surface_changed = _surface_changed;
        _surface_changed = false;
        int view_width = _view_width;
        int view_height = _view_height;
        pthread_mutex_unlock(&_mutex);

        if (!running) {
            break;
        }

        if (paused) {
            usleep(10000);
            continue;
        }

        if (surface_changed) {
            glViewport(0, 0, view_width, view_height);
        }

        if (_surface_texture) {
            ASurfaceTexture_updateTexImage(_surface_texture);
            ASurfaceTexture_getTransformMatrix(_surface_texture, _st_matrix);
        }
        draw_frame();
        eglSwapBuffers(_egl_display, _egl_surface);
        usleep(16000);
    }

    release_gl(env);
    release_egl();
    if (attached) {
        _java_vm->DetachCurrentThread();
    }
}

bool NativeRenderThread::init_egl()
{
    _egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (_egl_display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    if (!eglInitialize(_egl_display, NULL, NULL)) {
        LOGE("eglInitialize failed");
        return false;
    }

    const EGLint config_attribs[] = {
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_NONE
    };

    EGLConfig config;
    EGLint num_configs;
    if (!eglChooseConfig(_egl_display, config_attribs, &config, 1, &num_configs)) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint context_attribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
    };
    _egl_context = eglCreateContext(_egl_display, config, EGL_NO_CONTEXT, context_attribs);
    if (_egl_context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    pthread_mutex_lock(&_mutex);
    ANativeWindow* render_window = _render_window;
    pthread_mutex_unlock(&_mutex);
    if (!render_window) {
        LOGE("render window is null");
        return false;
    }

    _egl_surface = eglCreateWindowSurface(_egl_display, config, render_window, NULL);
    if (_egl_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }

    if (!eglMakeCurrent(_egl_display, _egl_surface, _egl_surface, _egl_context)) {
        LOGE("eglMakeCurrent failed");
        return false;
    }
    return true;
}

void NativeRenderThread::release_egl()
{
    if (_egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (_egl_surface != EGL_NO_SURFACE) {
            eglDestroySurface(_egl_display, _egl_surface);
            _egl_surface = EGL_NO_SURFACE;
        }
        if (_egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(_egl_display, _egl_context);
            _egl_context = EGL_NO_CONTEXT;
        }
        eglTerminate(_egl_display);
        _egl_display = EGL_NO_DISPLAY;
    }
}

bool NativeRenderThread::init_gl(JNIEnv* env)
{
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

    _program = create_program(VERTEX_SHADER, FRAGMENT_SHADER);
    if (_program == 0) {
        return false;
    }

    _position_handle = glGetAttribLocation(_program, "aPosition");
    _texture_handle = glGetAttribLocation(_program, "aTextureCoord");
    _mvp_matrix_handle = glGetUniformLocation(_program, "uMVPMatrix");
    _st_matrix_handle = glGetUniformLocation(_program, "uSTMatrix");
    if (_position_handle < 0 || _texture_handle < 0
            || _mvp_matrix_handle < 0 || _st_matrix_handle < 0) {
        LOGE("failed to get shader handles");
        return false;
    }

    glGenTextures(1, &_texture_id);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, _texture_id);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    check_gl_error("gl texture init");

    jclass surface_texture_class = env->FindClass("android/graphics/SurfaceTexture");
    jmethodID surface_texture_init = env->GetMethodID(surface_texture_class, "<init>", "(I)V");
    jobject surface_texture_object = env->NewObject(surface_texture_class,
            surface_texture_init, static_cast<jint>(_texture_id));
    _surface_texture_object = env->NewGlobalRef(surface_texture_object);
    env->DeleteLocalRef(surface_texture_object);

    _surface_texture = ASurfaceTexture_fromSurfaceTexture(env, _surface_texture_object);
    if (!_surface_texture) {
        LOGE("ASurfaceTexture_fromSurfaceTexture failed");
        return false;
    }

    jclass surface_class = env->FindClass("android/view/Surface");
    jmethodID surface_init = env->GetMethodID(surface_class, "<init>",
            "(Landroid/graphics/SurfaceTexture;)V");
    jobject codec_surface_object = env->NewObject(surface_class, surface_init,
            _surface_texture_object);
    _codec_surface_object = env->NewGlobalRef(codec_surface_object);
    env->DeleteLocalRef(codec_surface_object);

    _codec_window = ANativeWindow_fromSurface(env, _codec_surface_object);
    if (!_codec_window) {
        LOGE("ANativeWindow_fromSurface codec failed");
        return false;
    }

    pthread_mutex_lock(&_mutex);
    pthread_cond_broadcast(&_cond);
    pthread_mutex_unlock(&_mutex);
    return true;
}

void NativeRenderThread::release_gl(JNIEnv* env)
{
    pthread_mutex_lock(&_mutex);
    ANativeWindow* codec_window = _codec_window;
    _codec_window = NULL;
    pthread_cond_broadcast(&_cond);
    pthread_mutex_unlock(&_mutex);

    if (codec_window) {
        ANativeWindow_release(codec_window);
    }
    if (_surface_texture) {
        ASurfaceTexture_release(_surface_texture);
        _surface_texture = NULL;
    }
    if (_codec_surface_object) {
        env->DeleteGlobalRef(_codec_surface_object);
        _codec_surface_object = NULL;
    }
    if (_surface_texture_object) {
        env->DeleteGlobalRef(_surface_texture_object);
        _surface_texture_object = NULL;
    }
    if (_texture_id != 0) {
        glDeleteTextures(1, &_texture_id);
        _texture_id = 0;
    }
    if (_program != 0) {
        glDeleteProgram(_program);
        _program = 0;
    }
}

void NativeRenderThread::draw_frame()
{
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    glUseProgram(_program);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, _texture_id);

    glVertexAttribPointer(_position_handle, 3, GL_FLOAT, GL_FALSE,
            VERTICES_DATA_STRIDE_BYTES,
            VERTICES + VERTICES_DATA_POS_OFFSET);
    glEnableVertexAttribArray(_position_handle);

    glVertexAttribPointer(_texture_handle, 3, GL_FLOAT, GL_FALSE,
            VERTICES_DATA_STRIDE_BYTES,
            VERTICES + VERTICES_DATA_UV_OFFSET);
    glEnableVertexAttribArray(_texture_handle);

    glUniformMatrix4fv(_mvp_matrix_handle, 1, GL_FALSE, _mvp_matrix);
    glUniformMatrix4fv(_st_matrix_handle, 1, GL_FALSE, _st_matrix);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    check_gl_error("glDrawArrays");
}

int NativeRenderThread::load_shader(int shader_type, const char* source)
{
    GLuint shader = glCreateShader(shader_type);
    if (shader != 0) {
        glShaderSource(shader, 1, &source, NULL);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint info_len = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &info_len);
            if (info_len > 1) {
                char* info_log = new char[info_len];
                glGetShaderInfoLog(shader, info_len, NULL, info_log);
                LOGE("shader compile error: %s", info_log);
                delete[] info_log;
            }
            glDeleteShader(shader);
            shader = 0;
        }
    }
    return shader;
}

int NativeRenderThread::create_program(const char* vertex_source, const char* fragment_source)
{
    GLuint vertex_shader = load_shader(GL_VERTEX_SHADER, vertex_source);
    if (vertex_shader == 0) {
        return 0;
    }
    GLuint pixel_shader = load_shader(GL_FRAGMENT_SHADER, fragment_source);
    if (pixel_shader == 0) {
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program != 0) {
        glAttachShader(program, vertex_shader);
        glAttachShader(program, pixel_shader);
        glLinkProgram(program);
        GLint link_status = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &link_status);
        if (link_status != GL_TRUE) {
            GLint info_len = 0;
            glGetProgramiv(program, GL_INFO_LOG_LENGTH, &info_len);
            if (info_len > 1) {
                char* info_log = new char[info_len];
                glGetProgramInfoLog(program, info_len, NULL, info_log);
                LOGE("program link error: %s", info_log);
                delete[] info_log;
            }
            glDeleteProgram(program);
            program = 0;
        }
    }
    glDeleteShader(vertex_shader);
    glDeleteShader(pixel_shader);
    return program;
}

void NativeRenderThread::check_gl_error(const char* op)
{
    GLenum error;
    while ((error = glGetError()) != GL_NO_ERROR) {
        LOGE("%s: glError 0x%x", op, error);
    }
}

void NativeRenderThread::make_identity(float* matrix)
{
    for (int i = 0; i < 16; ++i) {
        matrix[i] = 0.f;
    }
    matrix[0] = 1.f;
    matrix[5] = 1.f;
    matrix[10] = 1.f;
    matrix[15] = 1.f;
}
