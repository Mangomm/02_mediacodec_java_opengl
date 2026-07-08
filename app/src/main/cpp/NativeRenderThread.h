#ifndef NATIVE_RENDER_THREAD_H
#define NATIVE_RENDER_THREAD_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_texture.h>
#include <android/surface_texture_jni.h>
#include <jni.h>
#include <pthread.h>

class NativeRenderThread
{
public:
    explicit NativeRenderThread(JavaVM* java_vm);
    ~NativeRenderThread();

    void set_render_window(ANativeWindow* window);
    void set_render_size(int width, int height);
    void set_paused(bool paused);
    void release_render_window();
    ANativeWindow* get_codec_window();

private:
    static void* thread_entry(void* obj);
    void thread_loop();

    bool init_egl();
    void release_egl();
    bool init_gl(JNIEnv* env);
    void release_gl(JNIEnv* env);
    void draw_frame();
    int load_shader(int shader_type, const char* source);
    int create_program(const char* vertex_source, const char* fragment_source);
    void check_gl_error(const char* op);

    void make_identity(float* matrix);

private:
    JavaVM* _java_vm;
    pthread_t _thread;
    pthread_mutex_t _mutex;
    pthread_cond_t _cond;

    bool _thread_started;
    bool _running;
    bool _paused;
    bool _surface_changed;
    int _view_width;
    int _view_height;

    ANativeWindow* _render_window;
    ANativeWindow* _codec_window;

    EGLDisplay _egl_display;
    EGLContext _egl_context;
    EGLSurface _egl_surface;

    GLuint _program;
    GLuint _texture_id;
    GLint _mvp_matrix_handle;
    GLint _st_matrix_handle;
    GLint _position_handle;
    GLint _texture_handle;

    ASurfaceTexture* _surface_texture;
    jobject _surface_texture_object;
    jobject _codec_surface_object;

    float _mvp_matrix[16];
    float _st_matrix[16];
};

#endif
