/// Implements module bindings
#include <stdlib.h>
#include <stdio.h>
#include <jni.h>
#include <pak.h>
#include <runtime.h>
#include <runtime_ext.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include "thread.h"
#include "main.h"

struct ModuleJavaStruct {
	struct PakModule *ptr;
};

static struct PakModule *create_module(JNIEnv *env, jobject o_mod) {
	struct PakModule *mod = calloc(1, sizeof(struct PakModule));
	mod->rt = calloc(sizeof(struct RuntimePriv), 1);
	mod->rt->log_buf = calloc(1, 1000); mod->rt->log_len = 1000;
	mod->rt->obj = (*env)->NewGlobalRef(env, o_mod);
	return mod;
}
static int init_module(JNIEnv *env, struct PakModule *mod) {
	mod->bt = pak_bt_get_context();
	mod->net = pak_net_get_context();

	struct ModuleJavaStruct temp = { .ptr = mod, };
	jbyteArray struct_o = (*env)->NewByteArray(env, sizeof(struct ModuleJavaStruct));
	(*env)->SetByteArrayRegion(env, struct_o, 0, sizeof(struct ModuleJavaStruct), (const jbyte *)&temp);

	jfieldID struct_field = (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/fudge/NativeModule"), "struct", "[B");
	(*env)->SetObjectField(env, mod->rt->obj, struct_field, struct_o);
	return 0;
}
static int free_module(JNIEnv *env, struct PakModule *mod) {
	if (mod->free) mod->free(mod);
	if (mod->bt) pak_bt_unref_context(mod->bt);
	if (mod->net) pak_net_unref_context(mod->net);
	(*env)->DeleteGlobalRef(env, mod->rt->obj);
	if (mod->rt->log_buf) free(mod->rt->log_buf);
	free(mod->rt);
	free(mod);
	return 0;
}

struct TempStruct {
	jobject byte_array;
	struct ModuleJavaStruct *data;
};
struct PakModule *get_mod(JNIEnv *env, jobject thiz, struct TempStruct *info) {
	set_jni_env_ctx(env, NULL);
	jclass thiz_c = (*env)->GetObjectClass(env, thiz);
	jfieldID struct_id = (*env)->GetFieldID(env, thiz_c, "struct", "[B");
	if (struct_id == NULL) abort();

	info->byte_array = (*env)->GetObjectField(env, thiz, struct_id);
	info->data = (struct ModuleJavaStruct *)(*env)->GetByteArrayElements(env, info->byte_array, NULL);
	return info->data->ptr;
}
void release_mod(JNIEnv *env, struct TempStruct *info) {
	(*env)->ReleaseByteArrayElements(env, info->byte_array, (jbyte *)info->data, 0);
}

JNIEXPORT void JNICALL
Java_dev_danielc_fudge_NativeModule_free(JNIEnv *env, jobject thiz) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);
	free_module(env, mod);
	release_mod(env, &info);
}

JNIEXPORT int JNICALL
Java_dev_danielc_fudge_NativeModule_init(JNIEnv *env, jobject thiz) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);
	int rc = 0;
	if (mod->init != NULL) rc = mod->init(mod);
	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onFindConnection(JNIEnv *env, jobject thiz, jint job) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	int rc = PAK_ERR_UNIMPLEMENTED;
	if (mod->on_find_connection) rc = mod->on_find_connection(mod, job);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onTryConnectWiFi(JNIEnv *env, jobject thiz, jobject adapter_o, jint job) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	struct PakWiFiAdapter *adapter = pak_wifi_adapter_from_jobject(env, adapter_o);

	int rc = PAK_ERR_UNIMPLEMENTED;
	if (mod->on_try_connect_wifi) rc = mod->on_try_connect_wifi(mod, adapter, NULL, job);

	release_mod(env, &info);
	return rc;
}

int pak_saved_info_from_jobject(JNIEnv *env, jobject saved_o, struct PakSavedConnection *saved) {
	(*env)->PushLocalFrame(env, 10);

	jclass saved_c = (*env)->FindClass(env, "dev/danielc/common/SavedDeviceInfo");

	jobject name_o = (*env)->GetObjectField(env, saved_o, (*env)->GetFieldID(env, saved_c, "name", "Ljava/lang/String;"));
	const char *name_s = (*env)->GetStringUTFChars(env, name_o, NULL);
	saved->name = strdup(name_s);
	(*env)->ReleaseStringUTFChars(env, name_o, name_s);

	jobject id_o = (*env)->GetObjectField(env, saved_o, (*env)->GetFieldID(env, saved_c, "uniqueIdentifier", "Ljava/lang/String;"));
	const char *id_s = (*env)->GetStringUTFChars(env, id_o, NULL);
	saved->unique_id = strdup(id_s);
	(*env)->ReleaseStringUTFChars(env, id_o, id_s);

	jobject data_o = (*env)->GetObjectField(env, saved_o, (*env)->GetFieldID(env, saved_c, "privateData", "[B"));
	jsize len = (*env)->GetArrayLength(env, data_o);
	uint8_t *data = malloc((size_t)len);
	(*env)->GetByteArrayRegion(env, data_o, 0, len, (jbyte *)data);

	saved->aux_data_length = (unsigned int)len;
	saved->aux_data = data;

	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

void free_saved_info(struct PakSavedConnection *saved) {
	free((char *)saved->name);
	free((char *)saved->unique_id);
	free((char *)saved->aux_data);
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onTryConnectBluetooth(JNIEnv *env, jobject thiz, jobject device_o, jobject saved_o,
													  jint job) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	struct PakSavedConnection saved;
	struct PakSavedConnection *saved_ptr = NULL;
	if (saved_o != NULL) {
		pak_saved_info_from_jobject(env, saved_o, &saved);
		saved_ptr = &saved;
	}

	struct PakBtDevice *device = pak_bt_device_from_jobject(env, mod->bt, device_o);

	int rc = PAK_ERR_UNIMPLEMENTED;
	if (mod->on_try_connect_bluetooth) rc = mod->on_try_connect_bluetooth(mod, device, saved_ptr, job);

	if (saved_ptr != NULL) free_saved_info(saved_ptr);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onIdleTick(JNIEnv *env, jobject thiz, jint us_since_last_tick) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	int rc = PAK_ERR_UNIMPLEMENTED;
	if (mod->on_idle_tick) rc = mod->on_idle_tick(mod, (unsigned int)us_since_last_tick);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onDisconnect(JNIEnv *env, jobject thiz) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	int rc = PAK_ERR_UNIMPLEMENTED;
	if (mod->on_switch_screen) rc = mod->on_disconnect(mod);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onSwitchScreen(JNIEnv *env, jobject thiz, jint old_screen,
													jint new_screen, jint job) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	int rc = PAK_ERR_UNIMPLEMENTED;
	if (mod->on_switch_screen) rc = mod->on_switch_screen(mod, old_screen, new_screen, job);

	release_mod(env, &info);
	return rc;
}

struct FileHandleWrapper {
	jobject storagename_o;
	struct PakFileHandle handle;
};

static struct FileHandleWrapper get_filehandle(JNIEnv *env, jobject file) {
	jclass filehandle_class = (*env)->FindClass(env, "dev/danielc/common/FileHandle");
	jfieldID storagename_f = (*env)->GetFieldID(env, filehandle_class, "storageName", "Ljava/lang/String;");
	jfieldID index_f = (*env)->GetFieldID(env, filehandle_class, "index", "I");
	jstring storagename_o = (*env)->GetObjectField(env, file, storagename_f);
	const char *storagename_s = NULL;
	if (storagename_o != NULL) storagename_s = (*env)->GetStringUTFChars(env, storagename_o, NULL);

	return (struct FileHandleWrapper){
		storagename_o,
		{
			.index_in_view = (*env)->GetIntField(env, file, index_f),
			.storage_name = storagename_s,
		},
	};
}

static void free_wrapper(JNIEnv *env, struct FileHandleWrapper *wrapper) {
	if (wrapper->storagename_o != NULL) (*env)->ReleaseStringUTFChars(env, wrapper->storagename_o, wrapper->handle.storage_name);
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onRequestFileThumbnail(JNIEnv *env, jobject thiz, jint job,
															jobject file) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	(*env)->PushLocalFrame(env, 10);

	struct FileHandleWrapper wrapper = get_filehandle(env, file);

	int rc = 0;
	if (mod->on_request_file_thumbnail) rc = mod->on_request_file_thumbnail(mod, job, &wrapper.handle);

	free_wrapper(env, &wrapper);
	(*env)->PopLocalFrame(env, NULL);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onRequestFileContents(JNIEnv *env, jobject thiz, jint job,
														   jobject file) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	(*env)->PushLocalFrame(env, 10);

	struct FileHandleWrapper wrapper = get_filehandle(env, file);

	int rc = 0;
	if (mod->on_request_file_contents) rc = mod->on_request_file_contents(mod, job, &wrapper.handle);

	free_wrapper(env, &wrapper);
	(*env)->PopLocalFrame(env, NULL);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_NativeModule_onRequestFileMetadata(JNIEnv *env, jobject thiz, jint job,
														   jobject file) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	(*env)->PushLocalFrame(env, 10);

	struct FileHandleWrapper wrapper = get_filehandle(env, file);

	int rc = 0;
	if (mod->on_request_file_metadata) rc = mod->on_request_file_metadata(mod, job, &wrapper.handle);

	free_wrapper(env, &wrapper);
	(*env)->PopLocalFrame(env, NULL);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL Java_dev_danielc_fudge_NativeModule_onRunCommand(JNIEnv *env, jobject thiz, jint job, jstring arg0, jstring arg1, jstring arg2, jstring arg3) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	(*env)->PushLocalFrame(env, 10);

	const char *list[4];
	int argc = 0;
	if (arg0 != NULL) list[argc++] = (*env)->GetStringUTFChars(env, arg0, NULL);
	if (arg1 != NULL) list[argc++] = (*env)->GetStringUTFChars(env, arg1, NULL);
	if (arg2 != NULL) list[argc++] = (*env)->GetStringUTFChars(env, arg2, NULL);
	if (arg3 != NULL) list[argc++] = (*env)->GetStringUTFChars(env, arg3, NULL);

	int rc = 0;
	if (mod->on_custom_command) rc = mod->on_custom_command(mod, job, argc, list);

	argc = 0;
	if (arg0 != NULL) (*env)->ReleaseStringUTFChars(env, arg0, list[argc++]);
	if (arg1 != NULL) (*env)->ReleaseStringUTFChars(env, arg1, list[argc++]);
	if (arg2 != NULL) (*env)->ReleaseStringUTFChars(env, arg2, list[argc++]);
	if (arg3 != NULL) (*env)->ReleaseStringUTFChars(env, arg3, list[argc++]);

	(*env)->PopLocalFrame(env, NULL);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT jint JNICALL Java_dev_danielc_fudge_NativeModule_onPropChanged(JNIEnv *env, jobject thiz, jint job, jobject pane) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	struct PakWidget setting = {
			.title = ""
	};

	(*env)->PushLocalFrame(env, 10);

	jobject args = (*env)->CallObjectMethod(env, pane, (*env)->GetMethodID(env, (*env)->FindClass(env, "dev/danielc/common/Widget"), "getArgs", "()Ldev/danielc/common/Widget$Properties;"));

	jclass properties_c = (*env)->FindClass(env, "dev/danielc/common/Widget$Properties");
	jfieldID name_f = (*env)->GetFieldID(env, properties_c, "name", "Ljava/lang/String;");
	jstring name_s = (*env)->GetObjectField(env, args, name_f);
	const char *name = (*env)->GetStringUTFChars(env, name_s, NULL);

	if ((*env)->IsInstanceOf(env, pane, (*env)->FindClass(env, "dev/danielc/common/Widget$Button"))) {
		setting.type = PAK_BUTTON;
	} else if ((*env)->IsInstanceOf(env, pane, (*env)->FindClass(env, "dev/danielc/common/Widget$BooleanSetting"))) {
		jfieldID value_f = (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/common/Widget$BooleanSetting"), "value", "Z");
		setting.type = PAK_BOOLEAN;
		setting.u.boolv.v = (*env)->GetBooleanField(env, (jobject)pane, value_f);
	}

	int rc = PAK_ERR_UNIMPLEMENTED;
	if (mod->on_setting_changed) rc = mod->on_setting_changed(mod, job, name, &setting);

	(*env)->ReleaseStringUTFChars(env, name_s, name);

	(*env)->PopLocalFrame(env, NULL);

	release_mod(env, &info);
	return rc;
}

JNIEXPORT void JNICALL
Java_dev_danielc_fudge_NativeModule_setSetupOptionName(JNIEnv *env, jobject thiz, jstring name) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);

	if (name == NULL) {
		mod->rt->setup_option = NULL;
	} else {
		if (mod->rt->setup_option != NULL) free(mod->rt->setup_option);
		const char *setup_option = (*env)->GetStringUTFChars(env, name, 0);
		mod->rt->setup_option = strdup(setup_option);
		(*env)->ReleaseStringUTFChars(env, name, setup_option);
	}

	release_mod(env, &info);
}

JNIEXPORT int JNICALL
Java_dev_danielc_fudge_AndroidRuntime_setupWebassemblyModule(JNIEnv *env, jclass clazz, jobject mod_o, jbyteArray fileContents) {
	set_jni_env_ctx(env, clazz);
	struct PakModule *mod = create_module(env, mod_o);
	jbyte *buf = (*env)->GetByteArrayElements(env, fileContents, NULL);
	jsize size = (*env)->GetArrayLength(env, fileContents);
	int rc = setup_wasm_module(mod, (char *)buf, (unsigned int)size);
	(*env)->ReleaseByteArrayElements(env, fileContents, buf, 0);
	if (rc) return rc;
	return init_module(env, mod);
	return -1;
}

JNIEXPORT int JNICALL
Java_dev_danielc_fudge_AndroidRuntime_setupJavascriptModule(JNIEnv *env, jclass clazz, jobject mod_o, jbyteArray fileContents) {
	set_jni_env_ctx(env, clazz);
	struct PakModule *mod = create_module(env, mod_o);
	jbyte *buf = (*env)->GetByteArrayElements(env, fileContents, NULL);
	jsize size = (*env)->GetArrayLength(env, fileContents);
	int rc = setup_quickjs_module(mod, (char *)buf, (unsigned int)(size - 1));
	(*env)->ReleaseByteArrayElements(env, fileContents, buf, 0);
	if (rc) return rc;
	return init_module(env, mod);
}

JNIEXPORT jint JNICALL
Java_dev_danielc_fudge_AndroidRuntime_setupSharedLibraryModule(JNIEnv *env, jclass clazz, jobject mod_o, jstring path) {
	set_jni_env_ctx(env, clazz);
	struct PakModule *mod = create_module(env, mod_o);
	const char *path_s = (*env)->GetStringUTFChars(env, path, NULL);

	void *lib = dlopen(path_s, RTLD_NOW);
	if (lib == NULL) {
		pak_debug_log(mod, "Failed to open %s", path_s);
		(*env)->ReleaseStringUTFChars(env, path, path_s);
		return -1;
	}
	void *ptr = dlsym(lib, "get_module");
	if (ptr == NULL) {
		pak_debug_log(mod, "Failed to get symbol get_module in %s", path_s);
		(*env)->ReleaseStringUTFChars(env, path, path_s);
		dlclose(lib);
		return -1;
	}

	int (*get_module)(struct PakModule *) = (int (*)(struct PakModule *))(uintptr_t)ptr;

	int rc = get_module(mod);

	mod->rt->lib = lib;

	(*env)->ReleaseStringUTFChars(env, path, path_s);
	return init_module(env, mod);
}

JNIEXPORT void JNICALL
Java_dev_danielc_fudge_NativeModule_updateNativeLiveview(JNIEnv *env, jobject thiz, jobject surface, jboolean is_paused) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);
	if (surface == NULL) {
		mod->rt->liveview.window = NULL;
	} else {
		if (mod->rt->liveview.window != NULL) ANativeWindow_release(mod->rt->liveview.window);
		mod->rt->liveview.window = ANativeWindow_fromSurface(env, surface);
		if (mod->rt->liveview.window != NULL)
			ANativeWindow_acquire(mod->rt->liveview.window);
		else
			__android_log_write(ANDROID_LOG_ERROR, "liveview", "ANativeWindow_fromSurface");
	}
	atomic_store(&mod->rt->liveview.liveview_cancel, is_paused);
	release_mod(env, &info);
}

JNIEXPORT void JNICALL
Java_dev_danielc_fudge_NativeModule_nativeLiveviewThread(JNIEnv *env, jobject thiz) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);
	__android_log_write(ANDROID_LOG_ERROR, "liveview", "Start liveview thread");
    struct timeval start, end;
	while (!atomic_load(&mod->rt->liveview.liveview_cancel)) {
		gettimeofday(&start, NULL);
		if (mod->on_request_liveview_frame(mod, -1, &(struct PakFileHandle){
			.storage_name = "liveview",
		})) break;
        gettimeofday(&end, NULL);

        long elapsed_us = (end.tv_sec - start.tv_sec) * 1000000L + (end.tv_usec - start.tv_usec);
		// We'll hardcode 30fps for now
        long sleep_time = (1000000 / 30) - elapsed_us;

        if (sleep_time > 0) {
            usleep(sleep_time);
        }
		usleep(1000000 / 30);
	}

	__android_log_write(ANDROID_LOG_ERROR, "liveview", "end liveview thread");
	release_mod(env, &info);
}

JNIEXPORT jstring JNICALL
Java_dev_danielc_fudge_NativeModule_getVerboseLog(JNIEnv *env, jobject thiz) {
	struct TempStruct info;
	struct PakModule *mod = get_mod(env, thiz, &info);
	jstring str = (*env)->NewString(env, (const jchar *)mod->rt->log_buf, 0);
	release_mod(env, &info);
	return str;
}