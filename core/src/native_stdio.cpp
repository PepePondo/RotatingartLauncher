#include <jni.h>
#include <unistd.h>

extern "C"
JNIEXPORT jint JNICALL
Java_com_app_ralaunch_core_common_util_NativeMethods_nativeChdir(JNIEnv *env, jclass clazz,
                                                                 jstring path) {
    if (path == nullptr) {
        return -1;
    }

    const char *nativePath = env->GetStringUTFChars(path, nullptr);
    if (nativePath == nullptr) {
        return -1;
    }

    int result = chdir(nativePath);

    env->ReleaseStringUTFChars(path, nativePath);

    return result;
}

// PortHub: games print their errors to stdout/stderr, which Android throws away. Copy both into logcat
// (tag "GameStdout") so a game that quits by itself can be diagnosed. Called once per game process.
#include <android/log.h>
#include <pthread.h>
#include <stdio.h>

static int porthub_stdio_pipe[2] = {-1, -1};

static void *porthub_stdio_reader(void *) {
    char buf[1024];
    char line[4096];
    size_t len = 0;
    ssize_t n;
    while ((n = read(porthub_stdio_pipe[0], buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n' || len == sizeof(line) - 1) {
                line[len] = 0;
                __android_log_write(ANDROID_LOG_INFO, "GameStdout", line);
                len = 0;
                if (buf[i] == '\n') continue;
            }
            line[len++] = buf[i];
        }
    }
    return nullptr;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_app_ralaunch_core_common_util_NativeMethods_nativeRedirectStdioToLogcat(JNIEnv *, jclass) {
    if (porthub_stdio_pipe[0] != -1) return 0;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(porthub_stdio_pipe) != 0) return -1;
    dup2(porthub_stdio_pipe[1], STDOUT_FILENO);
    dup2(porthub_stdio_pipe[1], STDERR_FILENO);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, porthub_stdio_reader, nullptr) != 0) return -1;
    pthread_detach(thread);
    return 0;
}
