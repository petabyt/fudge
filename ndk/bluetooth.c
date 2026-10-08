#include <stdlib.h>
#include <bluetooth.h>
#include <jni.h>
#include <android/log.h>
#include "ndk.h"
#include "pak_gc.h"

struct PakBt {
	struct GcContext gc;
};

struct PakBtAdapterPriv {
	jobject adapter;
};

struct PakBtDevicePriv {
	jobject device;
	jobject bluetooth_device;
	int setup_gatt_listener;
	struct PakBt *ctx;
	pak_bt_listen_device *cb;
	void *cb_arg;
};

struct PakBtSocket {
	struct PakBt *ctx;
	jobject socket;
	jobject output;
	jobject input;
};

struct PakGattServicePriv {
	struct PakBtDevice *device;
	jobject obj;
};
struct PakGattCharacteristicPriv {
	struct PakBtDevice *device;
	struct PakGattService *service;
	jobject obj;
};

static int read_uuid(JNIEnv *env, jobject uuid_o, char uuid[UUID_STR_LENGTH]) {
	(*env)->PushLocalFrame(env, 10);
	jstring str = (*env)->CallObjectMethod(env, uuid_o, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/util/UUID"), "toString", "()Ljava/lang/String;"));
	const char *s = (*env)->GetStringUTFChars(env, str, NULL);
	strncpy(uuid, s, UUID_STR_LENGTH);
	(*env)->ReleaseStringUTFChars(env, str, s);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

static int setup_listener(struct PakBtDevice *dev) {
	JNIEnv *env = get_jni_env();
	if (!dev->priv->setup_gatt_listener) {
		(*env)->PushLocalFrame(env, 10);

		jbyteArray struct_o = (*env)->NewByteArray(env, (jsize) sizeof(struct PakBtDevice *));
		(*env)->SetByteArrayRegion(env, struct_o, 0, (jsize) sizeof(struct PakBtDevice *), (const jbyte *)&dev);

		jclass device_c = (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device");
		jmethodID connect_m = (*env)->GetMethodID(env, device_c, "connectGattNative", "([B)I");
		int rc = (*env)->CallIntMethod(env, dev->priv->device, connect_m, struct_o);
		(*env)->PopLocalFrame(env, NULL);
		dev->priv->setup_gatt_listener = 1;
		return rc;
	}

	return 0;
}

int pak_bt_set_device_callback(struct PakBt *ctx, struct PakBtDevice *device, pak_bt_listen_device *cb, void *cb_arg) {
	device->priv->cb = cb;
	device->priv->cb_arg = cb_arg;
	return 0;
}

struct PakGattCharacteristic *characteristic_from_jobject(JNIEnv *env, struct PakBt *ctx, jobject chr_o) {
	struct PakGattCharacteristic *characteristic = calloc(1, sizeof(struct PakGattCharacteristic));

	jobject uuid_o = (*env)->CallObjectMethod(env, chr_o, (*env)->GetMethodID(env, (*env)->FindClass(env, "android/bluetooth/BluetoothGattCharacteristic"), "getUuid", "()Ljava/util/UUID;"));
	read_uuid(env, uuid_o, characteristic->uuid);

	characteristic->priv = calloc(1, sizeof(struct PakGattCharacteristicPriv));
	pak_gc_add(&ctx->gc, GATT_CHR, characteristic);
	characteristic->priv->obj = (*env)->NewGlobalRef(env, chr_o);
	return characteristic;
}

JNIEXPORT void JNICALL Java_dev_danielc_libpak_Bluetooth_00024NativeBluetoothGattCallback_onEvent(JNIEnv *env, jobject thiz, jint id, jobject chr) {
	set_jni_env_ctx(env, NULL);
	(*env)->PushLocalFrame(env, 10);
	struct PakBtDevice *dev = NULL;
	jfieldID field = (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$NativeBluetoothGattCallback"), "struct", "[B");
	jobject struct_o = (*env)->GetObjectField(env, thiz, field);
	(*env)->GetByteArrayRegion(env, struct_o, 0, (jsize)sizeof(struct PakBtDevice *), (jbyte *)&dev);

//	__android_log_print(ANDROID_LOG_ERROR, __func__, "%p %p", dev, dev->priv->ctx);

	if (dev->priv->cb != NULL) {
		struct PakGattCharacteristic *chr2 = (chr == NULL) ? NULL : characteristic_from_jobject(env, dev->priv->ctx, chr);
		dev->priv->cb(dev->priv->ctx, id, dev, chr2, dev->priv->cb_arg);
		if (chr != NULL) pak_bt_unref_gatt_characteristic(dev->priv->ctx, chr2);
	}

	(*env)->PopLocalFrame(env, NULL);
}

static jobject get_default_adapter(JNIEnv *env) {
	jclass btclass = (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth");
	jmethodID getdefaultadapter = (*env)->GetStaticMethodID(env, btclass, "getDefaultAdapter", "()Landroid/bluetooth/BluetoothAdapter;");
	return (*env)->CallStaticObjectMethod(env, btclass, getdefaultadapter);
}

struct PakBt *pak_bt_get_context(void) {
	struct PakBt *bt = malloc(sizeof(struct PakBt));
	pak_setup_gc(&bt->gc);
	return bt;
}

void pak_bt_unref_context(struct PakBt *ctx) {
	pak_gc_close(&ctx->gc);
	free(ctx);
}

int pak_bt_get_n_adapters(struct PakBt *ctx) {
	return 1;
}

struct PakBtAdapter *pak_bt_get_adapter(struct PakBt *ctx, int index) {
	JNIEnv *env = get_jni_env();
	struct PakBtAdapter *adapter = malloc(sizeof(struct PakBtAdapter));
	adapter->priv = malloc(sizeof(struct PakBtAdapterPriv));
	adapter->priv->adapter = (*env)->NewGlobalRef(env, get_default_adapter(env));
	pak_gc_add(&ctx->gc, BT_ADAPTER, adapter);
	return adapter;
}

int pak_bt_unref_adapter(struct PakBt *ctx, struct PakBtAdapter *adapter) {
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, adapter->priv->adapter);
	pak_gc_remove(&ctx->gc, adapter);
	free(adapter->priv);
	free(adapter);
	return 0;
}

static void free_dev(void *ptr, void *arg) {
	pak_bt_unref_device((struct PakBt *)arg, (struct PakBtDevice *)ptr);
}

int pak_bt_device_update(struct PakBt *ctx, struct PakBtDevice *dev) {
	JNIEnv *env = get_jni_env();
	jmethodID is_connected_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "isConnected", "()Z");
	dev->is_connected = (int)(*env)->CallBooleanMethod(env, dev->priv->device, is_connected_m);

	jmethodID is_bonded_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "isBonded", "()Z");
	dev->is_bonded = (int)(*env)->CallBooleanMethod(env, dev->priv->device, is_bonded_m);

	return 0;
}

struct PakBtDevice *pak_bt_device_from_jobject(JNIEnv *env, struct PakBt *ctx, jobject dev_o) {
	(*env)->PushLocalFrame(env, 10);

	struct PakBtDevice *device = calloc(sizeof(struct PakBtDevice), 1);
	device->priv = calloc(1, sizeof(struct PakBtDevicePriv));
	device->priv->ctx = ctx;
	pak_gc_add_ex(&ctx->gc, BT_DEVICE, device, free_dev, ctx);
	device->priv->device = (*env)->NewGlobalRef(env, dev_o);
	jfieldID dev_f = (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "dev", "Landroid/bluetooth/BluetoothDevice;");
	device->priv->bluetooth_device = (*env)->NewGlobalRef(env, (*env)->GetObjectField(env, dev_o, dev_f));
	pak_bt_device_update(NULL, device);

	jfieldID name_f = (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "name", "Ljava/lang/String;");
	jobject name_o = (*env)->GetObjectField(env, dev_o, name_f);
	const char *name_s = (*env)->GetStringUTFChars(env, name_o, NULL);
	strlcpy(device->name, name_s, sizeof(device->name));
	(*env)->ReleaseStringUTFChars(env, name_o, name_s);

	jobject address_o = (*env)->GetObjectField(env, dev_o, (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "address", "Ljava/lang/String;"));
	const char *address_s = (*env)->GetStringUTFChars(env, address_o, NULL);
	strlcpy(device->mac_address, address_s, sizeof(device->mac_address));
	(*env)->ReleaseStringUTFChars(env, address_o, address_s);

	pak_bt_device_update(ctx, device);

	(*env)->PopLocalFrame(env, NULL);
	return device;
}

static int scan_devices(struct PakBt *ctx, struct PakBtAdapter *adapter, int filter, struct PakBtDevice **device, int index) {
	JNIEnv *env = get_jni_env();

	(*env)->PushLocalFrame(env, 10);

	jclass btclass = (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth");
	jmethodID getbondeddevices = (*env)->GetStaticMethodID(env, btclass, "getBondedDevices", "(Landroid/bluetooth/BluetoothAdapter;)[Ldev/danielc/libpak/Bluetooth$Device;");
	jobjectArray array = (*env)->CallStaticObjectMethod(env, btclass, getbondeddevices, adapter->priv->adapter);
	if (array == NULL) {
		(*env)->PopLocalFrame(env, NULL);
		return -1;
	}

	int matching = 0;

	jsize n_dev = (*env)->GetArrayLength(env, array);
	for (jsize i = 0; i < n_dev; i++) {
		jobject dev_o = (*env)->GetObjectArrayElement(env, array, i);

		jmethodID is_connected_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "isConnected", "()Z");
		jboolean is_connected = (*env)->CallBooleanMethod(env, dev_o, is_connected_m);

		jmethodID is_bonded_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "isBonded", "()Z");
		jboolean is_bonded = (*env)->CallBooleanMethod(env, dev_o, is_bonded_m);

		int matches = is_connected && (filter & PAK_FILTER_CONNECTED);
		matches |= is_bonded && (filter & PAK_FILTER_BONDED);
		if (matches) {
			if (device != NULL && matching == index) {
				(*device) = pak_bt_device_from_jobject(env, ctx, dev_o);
				(*env)->PopLocalFrame(env, NULL);
				return 0;
			}
			matching++;
		}
	}

	(*env)->PopLocalFrame(env, NULL);

	if (device != NULL) return -1;
	return matching;
}

int pak_bt_get_n_devices(struct PakBt *ctx, struct PakBtAdapter *adapter, int filter) {
	return scan_devices(ctx, adapter, filter, NULL, 0);
}

struct PakBtDevice *pak_bt_get_device(struct PakBt *ctx, struct PakBtAdapter *adapter, int index, int filter) {
	struct PakBtDevice *device;
	if (scan_devices(ctx, adapter, filter, &device, index)) return NULL;
	return device;
}

int pak_bt_unref_device(struct PakBt *ctx, struct PakBtDevice *device) {
	JNIEnv *env = get_jni_env();
	pak_gc_remove(&ctx->gc, device);
	(*env)->CallVoidMethod(env, device->priv->device, (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "closeAll", "()V"));
	(*env)->DeleteGlobalRef(env, device->priv->device);
	(*env)->DeleteGlobalRef(env, device->priv->bluetooth_device);
	free(device->priv);
	free(device);
	return 0;
}

int pak_bt_device_connect(struct PakBt *ctx, struct PakBtDevice *device) {
	//if (device->is_classic) return PAK_ERR_NON_FATAL;
	return setup_listener(device);
}

int pak_bt_device_disconnect(struct PakBt *ctx, struct PakBtDevice *device) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	(*env)->CallVoidMethod(env, device->priv->device, (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "closeAll", "()V"));
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_bt_device_create_bond(struct PakBt *ctx, struct PakBtDevice *device) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	int rc = (*env)->CallIntMethod(env, device->priv->device, (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "createBond", "()I"));
	(*env)->PopLocalFrame(env, NULL);
	return rc;
}

struct PakBtSocket *pak_bt_connect_to_service_channel(struct PakBt *ctx, struct PakBtDevice *dev, const char *uuid) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass device = (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device");
	jmethodID connect_m = (*env)->GetMethodID(env, device, "connectToServiceChannel", "(Ljava/lang/String;)Landroid/bluetooth/BluetoothSocket;");
	jstring uuid_s = (*env)->NewStringUTF(env, uuid);
	jobject socket = (*env)->CallObjectMethod(env, dev->priv->device, connect_m, uuid_s);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		(*env)->ExceptionDescribe(env);
		(*env)->PopLocalFrame(env, NULL);
		return NULL;
	}
	if (socket == NULL) {
		(*env)->PopLocalFrame(env, NULL);
		return NULL;
	}

	jmethodID socket_connect_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "android/bluetooth/BluetoothSocket"), "connect", "()V");
	(*env)->CallVoidMethod(env, socket, socket_connect_m);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		(*env)->PopLocalFrame(env, NULL);
		pak_global_log("Failed to connect to socket");
		return NULL;
	}

	jmethodID get_output_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "android/bluetooth/BluetoothSocket"), "getOutputStream", "()Ljava/io/OutputStream;");
	jobject output_pipe = (*env)->CallObjectMethod(env, socket, get_output_m);
	if (output_pipe == NULL) {
		pak_global_log("getOutputStream");
		return NULL;
	}
	jmethodID get_input_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "android/bluetooth/BluetoothSocket"), "getInputStream", "()Ljava/io/InputStream;");
	jobject input_pipe = (*env)->CallObjectMethod(env, socket, get_input_m);
	if (input_pipe == NULL) {
		pak_global_log("getInputStream");
		return NULL;
	}

	struct PakBtSocket *conn = malloc(sizeof(struct PakBtSocket));

	pak_gc_add(&ctx->gc, BT_SOCKET, conn);
	conn->ctx = ctx;
	conn->socket = (*env)->NewGlobalRef(env, socket);
	conn->output = (*env)->NewGlobalRef(env, output_pipe);
	conn->input = (*env)->NewGlobalRef(env, input_pipe);
	(*env)->PopLocalFrame(env, NULL);
	return conn;
}

int pak_bt_write(struct PakBtSocket *conn, const void *data, unsigned int length) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass socket_c = (*env)->FindClass(env, "java/io/OutputStream");
	jmethodID write_m = (*env)->GetMethodID(env, socket_c, "write", "([B)V");
	jbyteArray data_o = (*env)->NewByteArray(env, (jsize)length);
	(*env)->SetByteArrayRegion(env, data_o, 0, (jsize)length, data);
	(*env)->CallVoidMethod(env, conn->output, write_m, data_o);
		if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		(*env)->ExceptionDescribe(env);
		(*env)->PopLocalFrame(env, NULL);
		return -1;
	}
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_bt_read(struct PakBtSocket *conn, void *data, unsigned int length) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass socket_c = (*env)->FindClass(env, "java/io/InputStream");
	jmethodID read_m = (*env)->GetMethodID(env, socket_c, "read", "([B)I");
	jbyteArray data_o = (*env)->NewByteArray(env, (jsize)length);
	(*env)->CallIntMethod(env, conn->input, read_m, data_o);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		(*env)->ExceptionDescribe(env);
		(*env)->PopLocalFrame(env, NULL);
		return -1;
	}
	(*env)->GetByteArrayRegion(env, data_o, 0, (jsize)length, data);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_bt_close_socket(struct PakBtSocket *conn) {
	JNIEnv *env = get_jni_env();

	jmethodID get_output_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "android/bluetooth/BluetoothSocket"), "close", "()V");
	(*env)->CallVoidMethod(env, conn->socket, get_output_m);

	(*env)->DeleteGlobalRef(env, conn->socket);
	(*env)->DeleteGlobalRef(env, conn->output);
	(*env)->DeleteGlobalRef(env, conn->input);
	pak_gc_remove(&conn->ctx->gc, conn);
	free(conn);
	return -1;
}

struct PakGattService *pak_bt_get_gatt_service(struct PakBt *ctx, struct PakBtDevice *device, int index) {
	JNIEnv *env = get_jni_env();
	int rc = setup_listener(device);
	if (rc) return NULL;
	(*env)->PushLocalFrame(env, 10);
	jclass device_c = (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device");
	jmethodID get_service_m = (*env)->GetMethodID(env, device_c, "getService", "(I)Landroid/bluetooth/BluetoothGattService;");
	jobject service_o = (*env)->CallObjectMethod(env, device->priv->device, get_service_m, index);
	if (service_o == NULL) {
		(*env)->PopLocalFrame(env, NULL);
		return NULL;
	}

	struct PakGattService *service = malloc(sizeof(struct PakGattService));

	jobject uuid_o = (*env)->CallObjectMethod(env, service_o, (*env)->GetMethodID(env, (*env)->FindClass(env, "android/bluetooth/BluetoothGattService"), "getUuid", "()Ljava/util/UUID;"));
	read_uuid(env, uuid_o, service->uuid);

	service->priv = calloc(1, sizeof(struct PakGattServicePriv));
	pak_gc_add(&ctx->gc, GATT_SVC, service);
	service->priv->obj = (*env)->NewGlobalRef(env, service_o);
	service->handle = 0;
	service->priv->device = device;

	(*env)->PopLocalFrame(env, NULL);
	return service;
}

int pak_bt_unref_gatt_service(struct PakBt *ctx, struct PakGattService *service) {
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, service->priv->obj);
	pak_gc_remove(&ctx->gc, service);
	free(service->priv);
	free(service);
	return 0;
}

struct PakGattCharacteristic *pak_bt_get_gatt_characteristic(struct PakBt *ctx, struct PakGattService *service, int index) {
	JNIEnv *env = get_jni_env();
	setup_listener(service->priv->device);
	(*env)->PushLocalFrame(env, 10);

	jobject chr_list = (*env)->CallObjectMethod(env, service->priv->obj, (*env)->GetMethodID(env, (*env)->FindClass(env, "android/bluetooth/BluetoothGattService"), "getCharacteristics", "()Ljava/util/List;"));
	jobject chr_o = (*env)->CallObjectMethod(env, chr_list, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/util/List"), "get", "(I)Ljava/lang/Object;"), index);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		(*env)->ExceptionDescribe(env);
		(*env)->PopLocalFrame(env, NULL);
		return NULL;
	}

	struct PakGattCharacteristic *characteristic = characteristic_from_jobject(env, ctx, chr_o);
	characteristic->priv->device = service->priv->device;
	characteristic->priv->service = service;

	(*env)->PopLocalFrame(env, NULL);
	return characteristic;
}

int pak_bt_unref_gatt_characteristic(struct PakBt *ctx, struct PakGattCharacteristic *chr) {
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, chr->priv->obj);
	pak_gc_remove(&ctx->gc, chr);
	free(chr->priv);
	free(chr);
	return 0;
}

int pak_bt_read_characteristic(struct PakBt *ctx, struct PakGattCharacteristic *characteristic, int blocking) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "readAsync", "(Landroid/bluetooth/BluetoothGattCharacteristic;)I");
	jint rc = (*env)->CallIntMethod(env, characteristic->priv->device->priv->device, read_m, characteristic->priv->obj);
	(*env)->PopLocalFrame(env, NULL);
	return rc;
}

unsigned int pak_bt_read_characteristic_cached_value(struct PakBt *ctx, struct PakGattCharacteristic *characteristic, uint8_t *buffer, unsigned int max) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "getCachedValue", "(Landroid/bluetooth/BluetoothGattCharacteristic;)[B");
	jbyteArray data = (*env)->CallObjectMethod(env, characteristic->priv->device->priv->device, read_m, characteristic->priv->obj);
	if (data == NULL) { (*env)->PopLocalFrame(env, NULL); return 0; }
	jsize len = (*env)->GetArrayLength(env, data);
	(*env)->GetByteArrayRegion(env, data, 0, (jsize)max < len ? (jsize)max : len, (jbyte *)buffer);
	(*env)->PopLocalFrame(env, NULL);
	return (unsigned int)len;
}

unsigned int pak_bt_get_manufacturer_data(struct PakBt *ctx, struct PakBtDevice *device, int index, uint8_t *buffer, unsigned int max) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "getManufacturerData", "(I)[B");
	jbyteArray data = (*env)->CallObjectMethod(env, device->priv->device, read_m, index);
	if (data == NULL) { (*env)->PopLocalFrame(env, NULL); return 0; }
	jsize len = (*env)->GetArrayLength(env, data);
	(*env)->GetByteArrayRegion(env, data, 0, (jsize)max < len ? (jsize)max : len, (jbyte *)buffer);
	(*env)->PopLocalFrame(env, NULL);
	return (unsigned int)len;
}

int pak_bt_write_characteristic(struct PakBt *ctx, struct PakGattCharacteristic *characteristic, const uint8_t *data, unsigned int length, int blocking) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);

	jbyteArray data_o = (*env)->NewByteArray(env, (jsize)length);
	(*env)->SetByteArrayRegion(env, data_o, 0, (jsize)length, (const jbyte *)data);

	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "writeAsync", "(Landroid/bluetooth/BluetoothGattCharacteristic;[B)I");
	jint rc = (*env)->CallIntMethod(env, characteristic->priv->device->priv->device, read_m, characteristic->priv->obj, data_o);
	(*env)->PopLocalFrame(env, NULL);
	return rc;
}

int pak_bt_set_watching_characteristic(struct PakBt *ctx, struct PakGattCharacteristic *characteristic, int v) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "setNotification", "(Landroid/bluetooth/BluetoothGattCharacteristic;Z)I");
	jint rc = (*env)->CallIntMethod(env, characteristic->priv->device->priv->device, read_m, characteristic->priv->obj, v);
	(*env)->PopLocalFrame(env, NULL);
	return rc;
}

int pak_bt_set_cccd(struct PakBt *ctx, struct PakGattCharacteristic *characteristic, int v) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "setCccd", "(Landroid/bluetooth/BluetoothGattCharacteristic;I)I");
	jint rc = (*env)->CallIntMethod(env, characteristic->priv->device->priv->device, read_m, characteristic->priv->obj, v);
	(*env)->PopLocalFrame(env, NULL);
	return rc;
}

int pak_bt_watch_characteristic(struct PakBt *ctx, struct PakGattCharacteristic *characteristic, unsigned int ms) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/libpak/Bluetooth$Device"), "waitBlocking", "(Landroid/bluetooth/BluetoothGattCharacteristic;I)I");
	jint rc = (*env)->CallIntMethod(env, characteristic->priv->device->priv->device, read_m, characteristic->priv->obj, (jint)ms);
	(*env)->PopLocalFrame(env, NULL);
	return rc;
}
