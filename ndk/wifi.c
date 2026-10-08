#include <stdlib.h>
#include <jni.h>
#include <wifi.h>
#include <android/log.h>
#include "ndk.h"

int android_setsocknetwork(jlong handle, int fd);

struct PakWiFiAdapterPriv {
	jobject network_o;
	jlong network_handle;
};

struct PakNet *pak_net_get_context(void) {
	return NULL;
}

void pak_net_unref_context(struct PakNet *ctx) {

}

int pak_wifi_get_n_adapters(struct PakNet *ctx) {
	return 1;
}

struct PakWiFiAdapter *pak_wifi_adapter_from_jobject(JNIEnv *env, jobject wifi_adapter) {
	(*env)->PushLocalFrame(env, 10);
	jclass adapter_c = (*env)->FindClass(env, "dev/danielc/libpak/WiFi$Adapter");
	jobject net_o = (*env)->GetObjectField(env, wifi_adapter, (*env)->GetFieldID(env, adapter_c, "net", "Landroid/net/Network;"));
	jlong handle = (*env)->GetLongField(env, wifi_adapter, (*env)->GetFieldID(env, adapter_c, "handle", "J"));

	struct PakWiFiAdapter *adapter = malloc(sizeof(struct PakWiFiAdapter));
	adapter->priv = calloc(1, sizeof(struct PakWiFiAdapterPriv));
	adapter->priv->network_handle = handle;
	adapter->priv->network_o = (*env)->NewGlobalRef(env, net_o);

	(*env)->PopLocalFrame(env, NULL);
	return adapter;
}

jobject pak_wifi_ap_filter_to_jobject(JNIEnv *env, struct PakWiFiApFilter *filter) {
	jclass filter_c = (*env)->FindClass(env, "dev/danielc/libpak/WiFi$ApFilter");
	jmethodID constructor = (*env)->GetMethodID(env, filter_c, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZ)V");
	return (*env)->NewObject(env, filter_c, constructor,
		filter->has_ssid ? (*env)->NewStringUTF(env, filter->ssid_pattern) : NULL,
		filter->has_bssid ? (*env)->NewStringUTF(env, filter->bssid) : NULL,
		filter->has_password ? (*env)->NewStringUTF(env, filter->password) : NULL,
		filter->has_band ? filter->band : -1,
		(jboolean)filter->is_hidden
	);
}

struct PakWiFiAdapter *pak_wifi_get_adapter(struct PakNet *ctx, int index) {
	if (index != 0 && index != -1) return NULL;
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);

	jclass wifi_c = (*env)->FindClass(env, "dev/danielc/libpak/WiFi");
	jobject adapter_o = (*env)->CallStaticObjectMethod(env, wifi_c, (*env)->GetStaticMethodID(env, wifi_c, "getPrimaryAdapter", "()Ldev/danielc/libpak/WiFi$Adapter;"));
	struct PakWiFiAdapter *adapter = pak_wifi_adapter_from_jobject(env, adapter_o);

	(*env)->PopLocalFrame(env, NULL);
	return adapter;
}
int pak_wifi_unref_adapter(struct PakNet *ctx, struct PakWiFiAdapter *adapter) {
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, adapter->priv->network_o);
	free(adapter->priv);
	free(adapter);
	return -1;
}

/// Bind a socket file descriptor to a network adapter
int pak_wifi_bind_socket_to_adapter(struct PakNet *ctx, struct PakWiFiAdapter *adapter, int fd) {
	__android_log_print(ANDROID_LOG_DEBUG, "wifi", "Binding %d to %ld", fd, adapter->priv->network_handle);
	return android_setsocknetwork(adapter->priv->network_handle, fd);
}

/// Request the adapter to perform a scan (roam)
int pak_wifi_request_scan(struct PakNet *ctx, struct PakWiFiAdapter *adapter) {
	return -1;
}

int pak_wifi_get_n_aps(struct PakNet *ctx, struct PakWiFiAdapter *adapter) {
	return -1;
}
struct PakWiFiAp *pak_wifi_get_ap(struct PakNet *ctx, struct PakWiFiAdapter *adapter, int index) {
	return NULL;
}
int pak_wifi_unref_ap(struct PakNet *ctx, struct PakWiFiAdapter *adapter, struct PakWiFiAp *ap) {
	return -1;
}

struct PakWiFiAp *pak_wifi_get_connected_ap(struct PakNet *ctx, struct PakWiFiAdapter *adapter) {
	return NULL;
}

int pak_wifi_connect_to_ap(struct PakNet *ctx, struct PakWiFiAdapter *adapter, struct PakWiFiAp *ap, const char *password) {
	return -1;
}

int pak_wifi_request_connection(struct PakNet *ctx, struct PakWiFiApFilter *spec, int (*cb)(struct PakNet *ctx, struct PakWiFiAdapter *, void *arg), void *arg) {
	return -1;
}

JNIEXPORT void JNICALL
Java_dev_danielc_libpak_WiFi_00024NativeWiFiDiscoveryCallback_onConnected(JNIEnv *env, jobject thiz,
																	jobject net) {
	// TODO: implement found()
}

JNIEXPORT void JNICALL
Java_dev_danielc_libpak_WiFi_00024NativeWiFiDiscoveryCallback_failed(JNIEnv *env, jobject thiz,
																	 jstring reason, jint code) {
	// TODO: implement failed()
}