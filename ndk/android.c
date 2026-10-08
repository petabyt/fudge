// Various JNI/Android helper functions
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <assert.h>
#include "ndk.h"

jint jni_get_res_id(JNIEnv *env, jobject ctx, const char *key, const char *name) {
	(*env)->PushLocalFrame(env, 10);
	jobject res = jni_get_resources(env, ctx);

	jmethodID get_identifier = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, res), "getIdentifier", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I");

	jstring key_s = (*env)->NewStringUTF(env, name);
	jstring name_s = (*env)->NewStringUTF(env, key);
	jstring pkg_s = jni_get_package_name(env, ctx);

	jint id = (*env)->CallIntMethod(
		env, res, get_identifier,
		key_s, name_s,
		pkg_s
	);

	(*env)->PopLocalFrame(env, NULL);

	return id;
}

jobject jni_get_display_metrics(JNIEnv *env, jobject ctx) {
	jclass cls_activity = (*env)->FindClass(env, "android/app/Activity");
	jclass cls_window_manager = (*env)->FindClass(env, "android/view/WindowManager");
	jclass cls_display_metrics = (*env)->FindClass(env, "android/util/DisplayMetrics");
	jclass cls_display = (*env)->FindClass(env, "android/view/Display");

	jmethodID mid_get_window_manager = (*env)->GetMethodID(env, cls_activity, "getWindowManager", "()Landroid/view/WindowManager;");
	jmethodID mid_get_default_display = (*env)->GetMethodID(env, cls_window_manager, "getDefaultDisplay", "()Landroid/view/Display;");
	jmethodID mid_get_metrics = (*env)->GetMethodID(env, cls_display, "getMetrics", "(Landroid/util/DisplayMetrics;)V");

	jobject wm = (*env)->CallObjectMethod(env, ctx, mid_get_window_manager);
	jobject display = (*env)->CallObjectMethod(env, wm, mid_get_default_display);

	jobject display_metrics = (*env)->NewObject(env, cls_display_metrics, (*env)->GetMethodID(env, cls_display_metrics, "<init>", "()V"));

	(*env)->CallVoidMethod(env, display, mid_get_metrics, display_metrics);
	return display_metrics;
}

jobject jni_get_main_looper(JNIEnv *env) {
	jclass c = (*env)->FindClass(env, "android/os/Looper");
	jmethodID m = (*env)->GetStaticMethodID(env, c, "getMainLooper", "()Landroid/os/Looper;");
	return (*env)->CallStaticObjectMethod(env, c, m);
}

jobject jni_get_handler(JNIEnv *env) {
	jobject looper = jni_get_main_looper(env);
	jclass handler_c = (*env)->FindClass(env, "android/os/Handler");
	jmethodID init = (*env)->GetMethodID(env, handler_c, "<init>", "(Landroid/os/Looper;)V");
	jobject handler = (*env)->NewObject(env, handler_c, init, looper);
	return handler;
}

jstring jni_get_package_name(JNIEnv *env, jobject context) {
	jmethodID get_package_name = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, context), "getPackageName", "()Ljava/lang/String;");
	return (*env)->CallObjectMethod(env, context, get_package_name);
}

jobject jni_get_resources(JNIEnv *env, jobject context) {
	jmethodID get_res = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, context), "getResources", "()Landroid/content/res/Resources;");
	return (*env)->CallObjectMethod(env, context, get_res);
}

jobject jni_get_drawable(JNIEnv *env, jobject ctx, int resid) {
	jobject res = jni_get_resources(env, ctx);
	jclass resources_class = (*env)->FindClass(env, "android/content/res/Resources");
	jmethodID get_drawable_method = (*env)->GetMethodID(env, resources_class, "getDrawable", "(I)Landroid/graphics/drawable/Drawable;");
	return (*env)->CallObjectMethod(env, res, get_drawable_method, resid);
}

void jni_toast(JNIEnv *env, jobject ctx, const char *string) {
	(*env)->PushLocalFrame(env, 10);

	jstring jbuffer = (*env)->NewStringUTF(env, string);

	jclass toast_c = (*env)->FindClass(env, "android/widget/Toast");
	jmethodID make_text_m = (*env)->GetStaticMethodID(env, toast_c, "makeText", "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;");
	jmethodID show_m = (*env)->GetMethodID(env, toast_c, "show", "()V");

	jobject toast = (*env)->CallStaticObjectMethod(env, toast_c, make_text_m, ctx, jbuffer, 0x0);
	(*env)->CallVoidMethod(env, toast, show_m);

	(*env)->PopLocalFrame(env, NULL);
}

jstring jni_concat_strings2(JNIEnv *env, jstring a, jstring b) {
	const char *a_ascii = (*env)->GetStringUTFChars(env, a, NULL);
	const char *b_ascii = (*env)->GetStringUTFChars(env, b, NULL);

	char *result = malloc(strlen(a_ascii) + strlen(b_ascii) + 1);
	strcpy(result, a_ascii);
	strcat(result, b_ascii);

	jstring result_s = (*env)->NewStringUTF(env, result);

	(*env)->ReleaseStringUTFChars(env, a, a_ascii);
	(*env)->ReleaseStringUTFChars(env, b, b_ascii);

	free(result);

	return result_s;
}

jstring jni_concat_strings3(JNIEnv *env, jstring a, jstring b, jstring c) {
	const char *a_ascii = (*env)->GetStringUTFChars(env, a, NULL);
	const char *b_ascii = (*env)->GetStringUTFChars(env, b, NULL);
	const char *c_ascii = (*env)->GetStringUTFChars(env, c, NULL);

	char *result = malloc(strlen(a_ascii) + strlen(b_ascii) + strlen(c_ascii) + 1);
	strcpy(result, a_ascii);
	strcat(result, b_ascii);
	strcat(result, c_ascii);

	jstring result_s = (*env)->NewStringUTF(env, result);

	(*env)->ReleaseStringUTFChars(env, a, a_ascii);
	(*env)->ReleaseStringUTFChars(env, b, b_ascii);
	(*env)->ReleaseStringUTFChars(env, c, c_ascii);

	free(result);

	return result_s;
}

void *jni_get_assets_file_contents(JNIEnv *env, jobject ctx, const char *filename, int *length) {
	(*env)->PushLocalFrame(env, 10);
	jmethodID get_assets_m = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, ctx), "getAssets", "()Landroid/content/res/AssetManager;");
	jobject asset_manager = (*env)->CallObjectMethod(env, ctx, get_assets_m);

	jstring jfile = (*env)->NewStringUTF(env, filename);

	jmethodID open_m = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, asset_manager), "open", "(Ljava/lang/String;)Ljava/io/InputStream;");
	jobject input_stream = (*env)->CallObjectMethod(env, asset_manager, open_m, jfile);

	jmethodID close_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/io/InputStream"), "close", "()V");
	jmethodID read_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/io/InputStream"), "read", "([B)I");
	jmethodID available_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/io/InputStream"), "available", "()I");

	int file_size = (*env)->CallIntMethod(env, input_stream, available_m);

	jbyteArray buffer = (*env)->NewByteArray(env, file_size);
	(*env)->CallIntMethod(env, input_stream, read_m, buffer);
	(*env)->CallVoidMethod(env, input_stream, close_m);

	jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, 0);
	(*length) = file_size;

	void *new = malloc(*length);
	memcpy(new, bytes, *length);

	(*env)->DeleteLocalRef(env, jfile);
	(*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);

	(*env)->PopLocalFrame(env, NULL);

	return new;
}

void *jni_get_txt_file(JNIEnv *env, jobject ctx, const char *filename) {
	int length = 0;
	char *bytes = jni_get_assets_file_contents(env, ctx, filename, &length);
	bytes = realloc(bytes, length + 1);
	bytes[length] = '\0';
	return bytes;
}

const char *jni_get_external_storage_path(JNIEnv *env) {
	(*env)->PushLocalFrame(env, 10);
	// Get File object for the external storage directory.
	jclass environment_c = (*env)->FindClass(env, "android/os/Environment");
	jmethodID method = (*env)->GetStaticMethodID(env, environment_c, "getExternalStorageDirectory", "()Ljava/io/File;");
	jobject file_obj = (*env)->CallStaticObjectMethod(env, environment_c, method);

	jmethodID get_path_m = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, file_obj), "getAbsolutePath", "()Ljava/lang/String;");
	jstring path = (*env)->CallObjectMethod(env, file_obj, get_path_m);

	(*env)->DeleteLocalRef(env, file_obj);

	path = (*env)->PopLocalFrame(env, path);
	return (*env)->GetStringUTFChars(env, path, 0);
}

jobject jni_get_application_ctx(JNIEnv *env) {
	jclass activity_thread = (*env)->FindClass(env,"android/app/ActivityThread");
	jmethodID current_activity_thread = (*env)->GetStaticMethodID(env, activity_thread, "currentActivityThread", "()Landroid/app/ActivityThread;");
	jobject activity_thread_obj = (*env)->CallStaticObjectMethod(env, activity_thread, current_activity_thread);

	jmethodID get_application = (*env)->GetMethodID(env, activity_thread, "getApplication", "()Landroid/app/Application;");
	jobject context = (*env)->CallObjectMethod(env, activity_thread_obj, get_application);
	return context;
}

char *jni_get_string(JNIEnv *env, jobject ctx, const char *key) {
	(*env)->PushLocalFrame(env, 10);
	jobject res = jni_get_resources(env, ctx);

	int id = jni_get_res_id(env, ctx, "string", key);
	if (id == 0) return (char *)"NULL";

	jmethodID get_string = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, res), "getString", "(I)Ljava/lang/String;");

	jstring val = (*env)->CallObjectMethod(
		env, res, get_string, id
	);

	const char *c_string = (*env)->GetStringUTFChars(env, val, 0);
	char *c_string2 = strdup(c_string);

	(*env)->ReleaseStringUTFChars(env, val, c_string);

	(*env)->PopLocalFrame(env, NULL);
	return c_string2;
}

int jni_get_string_id(JNIEnv *env, jobject ctx, const char *key) {
	(*env)->PushLocalFrame(env, 10);
	int id = jni_get_res_id(env, ctx, "string", key);
	if (id == 0) abort();
	(*env)->PopLocalFrame(env, NULL);
	return id;
}


//jbyteArray jni_struct_to_bytearray(void *data, int length) {
//	JNIEnv *env = get_jni_env();
//	jbyteArray arr = (*env)->NewByteArray(env, length);
//	(*env)->SetByteArrayRegion(env, arr, 0, length, (const jbyte *)data);
//	return arr;
//}
//
//jobject jni_string_to_jsonobject(JNIEnv *env, const char *str) {
//	jclass json_object_class;
//	jmethodID json_object_constructor;
//	jobject json_object;
//	jstring json_string;
//
//	json_object_class = (*env)->FindClass(env, "org/json/JSONObject");
//	json_object_constructor = (*env)->GetMethodID(env, json_object_class, "<init>", "(Ljava/lang/String;)V");
//
//	json_string = (*env)->NewStringUTF(env, str);
//	json_object = (*env)->NewObject(env, json_object_class, json_object_constructor, json_string);
//
//	(*env)->DeleteLocalRef(env, json_string);
//
//	return json_object;
//}
