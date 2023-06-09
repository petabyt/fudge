#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>

#include "jni.h"

#include <camlib.h>

struct AndroidBackend {
    jobject pac;
    jobject conn;
    JNIEnv *env;
    jobject main;

    jmethodID jni_print;
    jmethodID cmd_read;
    jmethodID cmd_write;
}backend;

struct PtpRuntime ptp_runtime;

void jni_print(char *fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    (*backend.env)->CallStaticVoidMethod(backend.env, backend.pac, backend.jni_print, (*backend.env)->NewStringUTF(backend.env, buffer));
}

JNI_FUNC(void, cInit)(JNIEnv *env, jobject thiz, jobject pac, jobject conn) {
    jclass thizClass = (*env)->GetObjectClass(env, thiz);
    jclass pacClass = (*env)->GetObjectClass(env, pac);
    backend.pac = (*env)->NewGlobalRef(env, pacClass);

    jclass connClass = (*env)->GetObjectClass(env, conn);
    backend.conn = (*env)->NewGlobalRef(env, connClass);

    // Ideally this is updated on every call, not supposed to turn into global ref?
    backend.env = env;

    backend.main = (*env)->NewGlobalRef(env, thiz);

    backend.jni_print = (*backend.env)->GetStaticMethodID(backend.env, pacClass, "jni_print", "(Ljava/lang/String;)V");
    backend.cmd_write = (*backend.env)->GetStaticMethodID(backend.env, connClass, "write", "([B)I");
    backend.cmd_read = (*backend.env)->GetStaticMethodID(backend.env, connClass, "read", "([BI)I");

    ptp_generic_init(&ptp_runtime);
    ptp_runtime.connection_type = PTP_IP;
}

JNI_FUNC(jint, cPtpFujiInit)(JNIEnv *env, jobject thiz) {
    backend.env = env;
    int rc = ptpip_fuji_init(&ptp_runtime, "fujiapp");

    struct PtpFujiInitResp resp;
    ptp_fuji_get_init_info(&ptp_runtime, &resp);

    jni_print("Connecting to %s\n", resp.cam_name);

    return rc;
}

JNI_FUNC(jstring, cPtpRun)(JNIEnv *env, jobject thiz, jstring string) {
    backend.env = env;
    const char *req = (*env)->GetStringUTFChars(env, string, 0);

    char *buffer = malloc(PTP_BIND_DEFAULT_SIZE);

    int r = bind_run(&ptp_runtime, (char *)req, buffer, PTP_BIND_DEFAULT_SIZE);

    if (r == -1) {
        return (*env)->NewStringUTF(env, "{\"error\": -1}");
    }

    jstring ret = (*env)->NewStringUTF(env, buffer);
    free(buffer);
    return ret;
}

JNI_FUNC(jint, cPtpFujiWaitUnlocked)(JNIEnv *env, jobject thiz) {
    backend.env = env;
    return ptpip_fuji_wait_unlocked(&ptp_runtime);
}

int ptpip_cmd_write(struct PtpRuntime *r, void *to, int length) {
    jbyteArray data = (*backend.env)->NewByteArray(backend.env, length);
    (*backend.env)->SetByteArrayRegion(backend.env, data, 0, length, (const jbyte *)(to));

    int ret = (*backend.env)->CallStaticIntMethod(backend.env, backend.conn, backend.cmd_write, data);

    if (ret < 0) {
        // TODO: debug
        return ret;
    }

    return ret;
}

int ptpip_cmd_read(struct PtpRuntime *r, void *to, int length) {
    jbyteArray data = (*backend.env)->NewByteArray(backend.env, length);
    int ret = (*backend.env)->CallStaticIntMethod(backend.env, backend.conn, backend.cmd_read, data, length);

    if (ret < 0) {
        //jnidbg("failed to recieve packet, %d", ret);
        return ret;
    }

    jbyte *bytes = (*backend.env)->GetByteArrayElements(backend.env, data, 0);
    memcpy(to, bytes, ret);
    return ret;
}

int ptpip_event_send(struct PtpRuntime *r, void *data, int size) {
    return -1;
}

int ptpip_event_read(struct PtpRuntime *r, void *data, int size) {
    return -1;
}