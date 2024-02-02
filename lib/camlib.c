// Portable Java bindings to camlib
#include <stdlib.h>
#include <jni.h>
#include <android/log.h>

#include <camlib.h>

#ifndef PTP_FUNC
#define PTP_FUNC(ret, name) JNIEXPORT ret JNICALL Java_camlib_CamlibBackend_##name
#endif

void set_jni_env(JNIEnv *env);
struct PtpRuntime *ptp_get();

PTP_FUNC(jstring, cPtpRun)(JNIEnv *env, jobject thiz, jstring string) {
	set_jni_env(env);
	struct PtpRuntime *r = ptp_get();

	const char *req = (*env)->GetStringUTFChars(env, string, 0);

	char *buffer = malloc(PTP_BIND_DEFAULT_SIZE);

	int rc = bind_run(r, (char *)req, buffer, PTP_BIND_DEFAULT_SIZE);

	if (rc == -1) {
		return (*env)->NewStringUTF(env, "{\"error\": -1}");
	}

	jstring ret = (*env)->NewStringUTF(env, buffer);
	free(buffer);
	return ret;
}

PTP_FUNC(jbyteArray, cPtpGetThumb)(JNIEnv *env, jobject thiz, jint handle) {
	set_jni_env(env);
	struct PtpRuntime *r = ptp_get();

	__android_log_write(ANDROID_LOG_ERROR, "camlib", "Trying to get thumbnail");

    int rc = ptp_get_thumbnail(r, (int)handle);
    if (rc == PTP_CHECK_CODE) {
        __android_log_write(ANDROID_LOG_ERROR, "camlib", "Thumbnail get failed");
        // If an error code is returned - allow it to fall
        // through and return a zero-length array
    } else if (rc) {
        return NULL;
    }

    jbyteArray ret = (*env)->NewByteArray(env, ptp_get_payload_length(r));
    (*env)->SetByteArrayRegion(env, ret, 0, ptp_get_payload_length(r), (const jbyte *)(ptp_get_payload(r)));
    return ret;
}

PTP_FUNC(jint, cPtpGetPropValue)(JNIEnv *env, jobject thiz, jint code) {
	set_jni_env(env);
	struct PtpRuntime *r = ptp_get();

	int rc = ptp_get_prop_value(r, code);
	if (rc < 0) {
		return rc;
	}

	return ptp_parse_prop_value(r);
}

PTP_FUNC(jint, cPtpOpenSession)(JNIEnv *env, jobject thiz) {
	set_jni_env(env);
	struct PtpRuntime *r = ptp_get();
	return ptp_open_session(r);
}

PTP_FUNC(jint, cPtpCloseSession)(JNIEnv *env, jobject thiz) {
	set_jni_env(env);
	struct PtpRuntime *r = ptp_get();
	return ptp_close_session(r);
}
