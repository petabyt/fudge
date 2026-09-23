#include <stdlib.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <pak.h>
#include <runtime.h>
#include <runtime_ext.h>
#include <dlfcn.h>
#include <stdint.h>
#include <android/log.h>
#include "thread.h"
#include "main.h"

JNIEXPORT void JNICALL Java_dev_danielc_fudge_AndroidRuntime_init(JNIEnv *env, jclass clazz) {
	set_jni_env_ctx(env, clazz);
	pak_global_log("AndroidRuntime init");
}

void pak_global_log(const char *fmt, ...) {
	char buffer[10000] = {0};
	va_list args;
	va_start(args, fmt);
	int b = vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);

	if (buffer[b - 1] == '\n') {
		buffer[b - 1] = '\0';
	}

	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jstring buffer_s = (*env)->NewStringUTF(env, buffer);
	jclass backend_c = (*env)->FindClass(env, "dev/danielc/fudge/AndroidRuntime");
	jmethodID log_global_m = (*env)->GetStaticMethodID(env, backend_c, "logGlobalLine", "(Ljava/lang/String;)V");
	(*env)->CallStaticVoidMethod(env, backend_c, log_global_m, buffer_s);
	(*env)->PopLocalFrame(env, NULL);
}

int pak_rt_set_tick_interval(struct PakModule *mod, unsigned int us) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID method = (*env)->GetMethodID(env, module_c, "setTickRate", "(I)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, method, (int)us);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

void pak_debug_log(struct PakModule *mod, const char *fmt, ...) {
	char buffer[4096] = {0};
	va_list args;
	va_start(args, fmt);
	int len = vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);

	if (buffer[len - 1] == '\n') buffer[len - 1] = '\0';

	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jstring buffer_s = (*env)->NewStringUTF(env, buffer);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID debug_log_m = (*env)->GetMethodID(env, module_c, "debugLog", "(Ljava/lang/String;)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, debug_log_m, buffer_s);
	(*env)->PopLocalFrame(env, NULL);

	__android_log_write(ANDROID_LOG_DEBUG, "pak_debug_log", buffer);
}

void pak_verbose_log(struct PakModule *mod, const char *fmt, ...) {
	char buffer[4096] = {0};
	va_list args;
	va_start(args, fmt);
	int len = vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);

	struct RuntimePriv *priv = mod->rt;
	if (len + priv->log_pos + 1 > priv->log_len) {
		priv->log_buf = realloc(priv->log_buf, len + priv->log_pos + 1);
		if (priv->log_buf == NULL) abort();
	}
	strcpy(priv->log_buf + priv->log_pos, buffer);
	priv->log_pos += len;

#ifndef NDEBUG
	__android_log_write(ANDROID_LOG_DEBUG, "pak_verbose_log", buffer);
#endif
}

void pak_rt_fatal_error(struct PakModule *mod, const char *fmt, ...) {
	char buffer[512] = {0};
	va_list args;
	va_start(args, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);

	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jstring buffer_s = (*env)->NewStringUTF(env, buffer);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID debug_log_m = (*env)->GetMethodID(env, module_c, "forceDisconnect", "(Ljava/lang/String;I)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, debug_log_m, buffer_s, 0);
	(*env)->PopLocalFrame(env, NULL);
}

void pak_error(const char *fmt, ...) {
	char buffer[512] = {0};
	va_list args;
	va_start(args, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);

	__android_log_write(ANDROID_LOG_ERROR, "pak_error", buffer);
}

void pak_abort(const char *fmt, ...) {
	char buffer[512];
	va_list args;
	va_start(args, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);

	__android_log_write(ANDROID_LOG_ERROR, "pak_abort", buffer);
	abort();
}

static jobject create_filehandle(JNIEnv *env, const struct PakFileHandle *file) {
	if (file == NULL) return NULL;
	(*env)->PushLocalFrame(env, 10);
	jclass filehandle_c = (*env)->FindClass(env, "dev/danielc/common/FileHandle");
	jmethodID constructor = (*env)->GetMethodID(env, filehandle_c, "<init>", "(ILjava/lang/String;Ljava/lang/String;)V");
	jobject handle_o = (*env)->NewObject(env, filehandle_c, constructor,
		file->index_in_view,
		file->storage_name == NULL ? NULL : (*env)->NewStringUTF(env, file->storage_name),
		file->path == NULL ? NULL : (*env)->NewStringUTF(env, file->path)
	);
	return (*env)->PopLocalFrame(env, handle_o);
}

static jobject create_filemetadata(JNIEnv *env, const struct PakFileMetadata *meta) {
	if (meta == NULL) return NULL;
	(*env)->PushLocalFrame(env, 10);
	jclass metadata_c = (*env)->FindClass(env, "dev/danielc/common/FileMetadata");
	jmethodID constructor = (*env)->GetMethodID(env, metadata_c, "<init>", "(Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;Ljava/lang/String;I)V");
	jobject handle_o = (*env)->NewObject(env, metadata_c, constructor,
		(*env)->NewStringUTF(env, meta->filename),
		(*env)->NewStringUTF(env, meta->mime_type),
		meta->image_width,
		meta->image_height,
		(jint)meta->file_size, // narrows
		NULL,
		NULL,
		meta->orientation
	);
	return (*env)->PopLocalFrame(env, handle_o);
}

const char *pak_rt_get_client_name(void) {
	JNIEnv *env = get_jni_env();
	static const char *cstr = NULL;
	if (cstr == NULL) {
		(*env)->PushLocalFrame(env, 10);
		jclass module_c = (*env)->FindClass(env, "dev/danielc/fudge/AndroidRuntime");
		jmethodID is_job_cancelled = (*env)->GetStaticMethodID(env, module_c, "getDeviceFriendlyName", "()Ljava/lang/String;");
		jstring s = (*env)->CallStaticObjectMethod(env, module_c, is_job_cancelled);
		cstr = (*env)->GetStringUTFChars(env, s, NULL);
		(*env)->PopLocalFrame(env, NULL);
	}
	return cstr;
}

int pak_rt_set_screen_supported(struct PakModule *mod, int screen, int v) {
	JNIEnv *env = get_jni_env();
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_screen_supported = (*env)->GetMethodID(env, module_c, "setScreenSupported", "(IZ)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, set_screen_supported, screen, (jboolean)v);
	return 0;
}

int pak_rt_is_job_cancelled(struct PakModule *mod, int job) {
	JNIEnv *env = get_jni_env();
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID is_job_cancelled = (*env)->GetMethodID(env, module_c, "isJobCancelled", "(I)Z");
	return (*env)->CallBooleanMethod(env, mod->rt->obj, is_job_cancelled, job);
}

int pak_rt_set_progress_bar(struct PakModule *mod, int job, int percent) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_progress_bar = (*env)->GetMethodID(env, module_c, "setProgressBar", "(II)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, set_progress_bar, job, percent);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_set_download_stats(struct PakModule *mod, int job, long time, unsigned int n_bytes) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_progress_bar = (*env)->GetMethodID(env, module_c, "setDownloadStats", "(IJI)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, set_progress_bar, job, (jlong)time, (jint)n_bytes);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_set_storage_info(struct PakModule *mod, const char *name, struct PakStorageInfo *info) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);

	jclass info_c = (*env)->FindClass(env, "dev/danielc/common/StorageInfo");
	jmethodID constructor = (*env)->GetMethodID(env, info_c, "<init>", "(Ljava/lang/String;IIJJZ)V");
	jobject handle_o = (*env)->NewObject(env, info_c, constructor,
		(*env)->NewStringUTF(env, name),
		(jint)info->n_files_total,
		(jint)info->sorted_by,
		(jlong)info->size_bytes,
		(jlong)info->used_bytes,
		(jboolean)info->is_live
	);

	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_storage_info = (*env)->GetMethodID(env, module_c, "setStorageInfo", "(Ldev/danielc/common/StorageInfo;)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, set_storage_info, handle_o);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_add_file_contents(struct PakModule *mod, struct PakFileHandle *file, void *image_data, unsigned int length, uint64_t offset, uint64_t total_size) {
	JNIEnv *env = get_jni_env();
	if (file->storage_name != NULL && !strcmp(file->storage_name, "liveview")) {
		return liveview_render_frame(env, &mod->rt->liveview, image_data, length);
	}

	(*env)->PushLocalFrame(env, 10);
	jobject handle_o = create_filehandle(env, file);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_storage_info = (*env)->GetMethodID(env, module_c, "setFileContents", "(Ldev/danielc/common/FileHandle;[BJJ)V");

	jbyteArray image_data_o = (image_data == NULL || length == 0) ? NULL : (*env)->NewByteArray(env, (jsize)length);
	if (image_data_o != NULL) (*env)->SetByteArrayRegion(env, image_data_o, 0, (jsize)length, (const jbyte *)image_data);

	(*env)->CallVoidMethod(env, mod->rt->obj, set_storage_info, handle_o, image_data_o, (jlong)offset, (jlong)total_size);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_add_file_thumbnail(struct PakModule *mod, struct PakFileHandle *file, void *image_data, unsigned int length) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jobject handle_o = create_filehandle(env, file);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_storage_info = (*env)->GetMethodID(env, module_c, "addFileThumbnail", "(Ldev/danielc/common/FileHandle;[B)V");

	jbyteArray image_data_o = image_data == NULL ? NULL : (*env)->NewByteArray(env, (jsize)length);
	if (image_data_o != NULL) (*env)->SetByteArrayRegion(env, image_data_o, 0, (jsize)length, (const jbyte *)image_data);

	(*env)->CallVoidMethod(env, mod->rt->obj, set_storage_info, handle_o, image_data_o);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_set_session_property(struct PakModule *mod, const char *key, const char *value) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_screen_supported = (*env)->GetMethodID(env, module_c, "setProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
	jstring key_s = (*env)->NewStringUTF(env, key);
	jstring value_s = (*env)->NewStringUTF(env, value);
	if (value_s == NULL && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
		return -1;
    }
	(*env)->CallVoidMethod(env, mod->rt->obj, set_screen_supported, key_s, value_s);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_set_session_property_int(struct PakModule *mod, const char *key, int value) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID set_screen_supported = (*env)->GetMethodID(env, module_c, "setProperty", "(Ljava/lang/String;I)V");
	jstring key_s = (*env)->NewStringUTF(env, key);
	(*env)->CallVoidMethod(env, mod->rt->obj, set_screen_supported, key_s, value);
	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_set_widget(struct PakModule *mod, const char *name, const struct PakWidget *s) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jclass properties_c = (*env)->FindClass(env, "dev/danielc/common/Widget$Properties");
	jstring name_s = (*env)->NewStringUTF(env, name);
	jstring title_s = (*env)->NewStringUTF(env, s->title);
	jobject properties_o = (*env)->NewObject(env, properties_c, (*env)->GetMethodID(env, properties_c, "<init>", "(Ljava/lang/String;Ljava/lang/String;I)V"), name_s, title_s, (jint)s->group);

	jobject pane_o = NULL;
	if (s->type == PAK_BOOLEAN) {
		jclass booleansetting_c = (*env)->FindClass(env, "dev/danielc/common/Widget$BooleanSetting");
		pane_o = (*env)->NewObject(env, booleansetting_c, (*env)->GetMethodID(env, booleansetting_c, "<init>", "(Ldev/danielc/common/Widget$Properties;Z)V"), properties_o, (jboolean)s->u.boolv.v);
	} else if (s->type == PAK_BUTTON) {
		jclass booleansetting_c = (*env)->FindClass(env, "dev/danielc/common/Widget$Button");
		pane_o = (*env)->NewObject(env, booleansetting_c, (*env)->GetMethodID(env, booleansetting_c, "<init>", "(Ldev/danielc/common/Widget$Properties;)V"), properties_o);
	} else if (s->type == PAK_GRAPH) {
		jclass booleansetting_c = (*env)->FindClass(env, "dev/danielc/common/Widget$Graph");
		jintArray arr = (*env)->NewIntArray(env, (jsize)s->u.graphv.n_points);
		(*env)->SetIntArrayRegion(env, arr, 0, (jsize)s->u.graphv.n_points, (const jint *)s->u.graphv.points);
		pane_o = (*env)->NewObject(env, booleansetting_c, (*env)->GetMethodID(env, booleansetting_c, "<init>", "(Ldev/danielc/common/Widget$Properties;[I)V"), properties_o, arr);
	} else if (s->type == PAK_DROPDOWN) {
		jclass booleansetting_c = (*env)->FindClass(env, "dev/danielc/common/Widget$DropdownSetting");

		int n;
		for (n = 0; s->u.dropdownv.list[n] != NULL; n++);

		jintArray arr = (*env)->NewObjectArray(env, (jsize)n, (*env)->FindClass(env, "java/lang/String"), NULL);
		for (int i = 0; i < n; i++) {
			(*env)->SetObjectArrayElement(env, arr, i, (*env)->NewStringUTF(env, s->u.dropdownv.list[i]));
		}
		pane_o = (*env)->NewObject(env, booleansetting_c, (*env)->GetMethodID(env, booleansetting_c, "<init>", "(Ldev/danielc/common/Widget$Properties;I[Ljava/lang/String;)V"), properties_o,
			(jint)s->u.dropdownv.index_value,
			arr
		);
	}
	if (pane_o != NULL) {
		jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
		jmethodID add_setting = (*env)->GetMethodID(env, module_c, "setDashboardPane", "(Ldev/danielc/common/Widget;)V");
		(*env)->CallVoidMethod(env, mod->rt->obj, add_setting, pane_o);
	}

	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_add_file_metadata(struct PakModule *mod, struct PakFileHandle *file, const struct PakFileMetadata *metadata) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);

	jobject handle_o = create_filehandle(env, file);
	jobject metadata_o = create_filemetadata(env, metadata);

	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID method = (*env)->GetMethodID(env, module_c, "addFileMetadata", "(Ldev/danielc/common/FileHandle;Ldev/danielc/common/FileMetadata;)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, method, handle_o, metadata_o);

	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_save_session_signature(struct PakModule *mod, struct PakSavedConnection *info) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);

	jbyteArray data = NULL;
	if (info->aux_data != NULL) {
		data = (*env)->NewByteArray(env, (jsize)info->aux_data_length);
		(*env)->SetByteArrayRegion(env, data, 0, (jsize)info->aux_data_length, (const jbyte *)info->aux_data);
	}

	jclass saveddeviceinfo_c = (*env)->FindClass(env, "dev/danielc/common/SavedDeviceInfo");
	jobject obj = (*env)->NewObject(env, saveddeviceinfo_c, (*env)->GetMethodID(env, saveddeviceinfo_c, "<init>", "(Ljava/lang/String;Ljava/lang/String;[B)V"),
		(*env)->NewStringUTF(env, info->unique_id),
		(*env)->NewStringUTF(env, info->name),
		data
	);

	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID method = (*env)->GetMethodID(env, module_c, "saveDeviceSignature", "(Ldev/danielc/common/SavedDeviceInfo;)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, method, obj);

	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_test_module(struct PakModule *mod) {
	return 0;
}

static char *strdup_jstring(JNIEnv *env, jstring s_o) {
	const char *s = (*env)->GetStringUTFChars(env, s_o, 0);
	char *s2 = strdup(s);
	(*env)->ReleaseStringUTFChars(env, s_o, s);
	return s2;
}

const char *pak_rt_get_setup_option(struct PakModule *mod) {
	return mod->rt->setup_option;
}

struct PakFileMetadata *pak_rt_get_metadata(struct PakModule *mod, struct PakFileHandle *file) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);
	jobject handle_o = create_filehandle(env, file);
	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID method = (*env)->GetMethodID(env, module_c, "getMetadata", "(Ldev/danielc/common/FileHandle;)Ldev/danielc/common/FileMetadata;");
	jobject md_o = (*env)->CallObjectMethod(env, mod->rt->obj, method, handle_o);
	if (md_o == NULL) {
		(*env)->PushLocalFrame(env, 10);
		return NULL;
	}

	struct PakFileMetadata *md = malloc(sizeof(struct PakFileMetadata));

	jclass md_c = (*env)->FindClass(env, "dev/danielc/common/FileMetadata");
	jobject filename_o = (*env)->GetObjectField(env, md_o, (*env)->GetFieldID(env, md_c, "filename", "Ljava/lang/String;"));
	md->filename = strdup_jstring(env, filename_o);
	md->file_size = (*env)->GetIntField(env, md_o, (*env)->GetFieldID(env, md_c, "filesize", "I"));

	(*env)->PopLocalFrame(env, NULL);
	return md;
}

void pak_rt_release_metadata(struct PakModule *mod, struct PakFileMetadata *md) {
	free((char *)md->filename);
	free(md);
}

int pak_rt_add_wifi_connection(struct PakModule *mod, struct PakWiFiApFilter *filter, const char *setup_option) {
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 10);

	jobject filter_o = pak_wifi_ap_filter_to_jobject(env, filter);

	jclass module_c = (*env)->FindClass(env, "dev/danielc/common/ModuleInstance");
	jmethodID method = (*env)->GetMethodID(env, module_c, "addWiFiConnection", "(Ldev/danielc/libpak/WiFi$ApFilter;Ljava/lang/String;)V");
	(*env)->CallVoidMethod(env, mod->rt->obj, method, filter_o,
		(*env)->NewStringUTF(env, setup_option)
	);

	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int pak_rt_add_folder_info(struct PakModule *mod, const char *storage_name, const char *folder_path, unsigned int n_items, enum PakSortedBy sorted_by) {
	// TODO:
	return -1;
}

JNIEXPORT jobject JNICALL Java_dev_danielc_fudge_InstrumentedTest_getTestWiFiApFilter(JNIEnv *env, jobject thiz) {
	struct PakWiFiApFilter filter = {0};
	filter.has_ssid = 1;
	strcpy(filter.ssid_pattern, "Test.*");
	filter.is_hidden = 1;
	filter.has_password = 1;
	strcpy(filter.password, "123456789");
	return pak_wifi_ap_filter_to_jobject(env, &filter);
	//(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/Exception"), "Failed assert");
}