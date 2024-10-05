#ifndef BACKEND_H
#define BACKEND_H

#include <jni.h>
#include <android/log.h>

#ifndef JNI_FUNC
#define JNI_FUNC(ret, name) JNIEXPORT ret JNICALL Java_dev_danielc_fujiapp_Backend_##name
#endif

#if defined(__arm__)
#if defined(__ARM_ARCH_7A__)
#if defined(__ARM_NEON__)
#if defined(__ARM_PCS_VFP)
#define ABI "armeabi-v7a/NEON (hard-float)"
#else
#define ABI "armeabi-v7a/NEON"
#endif
#else
#if defined(__ARM_PCS_VFP)
	#define ABI "armeabi-v7a (hard-float)"
	#else
	#define ABI "armeabi-v7a"
	#endif
#endif
#else
#define ABI "armeabi"
#endif
#elif defined(__i386__)
#define ABI "x86"
#elif defined(__x86_64__)
#define ABI "x86_64"
	#elif defined(__mips64)  /* mips64el-* toolchain defines __mips__ too */
	#define ABI "mips64"
	#elif defined(__mips__)
	#define ABI "mips"
	#elif defined(__aarch64__)
	#define ABI "arm64-v8a"
	#else
	#define ABI "unknown"
#endif

struct AndroidBackend {
	jobject progress_bar;
	int do_download;
	int download_progress;
	int download_size;

	// Only a single PTP connection supported
	struct PtpRuntime r;

	void *log_buf;
	size_t log_size;
	size_t log_pos;
	int last_percent;
};

extern struct AndroidBackend backend;

// Thread safe JNIEnv storage
void invalidate_jni_env_ctx(void);
void set_jni_env_ctx(JNIEnv *env, jobject ctx);
JNIEnv *get_jni_env(void);
jobject get_jni_ctx(void);
#define set_jni_env(x) set_jni_env_ctx(x, NULL)

// Verbose print to log file
void jni_verbose_log(char *str);

int jni_setup_usb(JNIEnv *env, jobject obj);

#endif
