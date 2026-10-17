#ifndef FUDGE_MAIN_H
#define FUDGE_MAIN_H
#include <jni.h>
#include <pak.h>
#include <runtime.h>
#include <stdatomic.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

struct SurfacePriv {
	ANativeWindow *window;
	pthread_mutex_t lock;
	atomic_bool liveview_cancel;
};

struct RuntimePriv {
	// NativeModule global handle
	jobject obj;
	char *setup_option;
	// dlopen handle
	void *lib;
	// TODO: Multiple liveviews
	struct SurfacePriv liveview;

	unsigned int log_len;
	unsigned int log_pos;
	char *log_buf;
};

jobject pak_wifi_ap_filter_to_jobject(JNIEnv *env, struct PakWiFiApFilter *filter);
struct PakBtDevice *pak_bt_device_from_jobject(JNIEnv *env, struct PakBt *ctx, jobject dev_o);
struct PakWiFiAdapter *pak_wifi_adapter_from_jobject(JNIEnv *env, jobject wifi_adapter);
libusb_device *pak_libusb_device_from_jobject(JNIEnv *env, jobject dev_o);

int liveview_render_frame(JNIEnv *env, struct SurfacePriv *priv, void *buffer, unsigned int size);

#endif