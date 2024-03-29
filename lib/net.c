// Rewrite of SimpleSocket backend - 40% faster!
// Copyright 2023 by Daniel C (https://github.com/petabyt/camlib)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/tcp.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <camlib.h>
#include <time.h>
#include <dlfcn.h>
#include "app.h"
#include "fuji.h"
#include "backend.h"

#define CMD_BUFFER_SIZE 512

struct PtpIpBackend {
	int fd;
	int evfd;
	int vidfd;
};

static jlong get_handle() {
	JNIEnv *env = get_jni_env();

	jclass class = (*env)->FindClass(env, "camlib/WiFiComm");
	jmethodID get_handle_m = (*env)->GetStaticMethodID(env, class, "getNetworkHandle", "()J");
	jlong handle = (*env)->CallStaticLongMethod(env, class, get_handle_m);
	return handle;
}

int ndk_set_socket_wifi(int fd) {
	typedef int (*_android_setsocknetwork_td)(jlong handle, int fd);

	// https://developer.android.com/ndk/reference/group/networking#android_setsocknetwork
	void *lib = dlopen("libandroid.so", RTLD_NOW);
	_android_setsocknetwork_td _android_setsocknetwork = (_android_setsocknetwork_td)dlsym(lib, "android_setsocknetwork");

	if (_android_setsocknetwork == NULL) {
		return -1;
	}

	jlong handle = get_handle();
	if (handle < 0) {
		return handle;
	}

	int rc = _android_setsocknetwork(handle, fd);

	dlclose(lib);

	return rc;
}

static int set_nonblocking_io(int sockfd, int enable) {
	int flags = fcntl(sockfd, F_GETFL, 0);
	if (flags == -1)
		return -1;

	if (enable) {
		flags |= O_NONBLOCK;
	} else {
		flags &= ~O_NONBLOCK;
	}

	return fcntl(sockfd, F_SETFL, flags);
}

#define ptp_verbose_log plat_dbg

int ptpip_new_timeout_socket(char *addr, int port, long timeout_sec) {
	int sockfd = socket(AF_INET, SOCK_STREAM, 0);

	int rc = ndk_set_socket_wifi(sockfd);
	if (rc) {
		return rc;
	}

	int yes = 1;
	setsockopt(
			sockfd,
			IPPROTO_TCP,
			TCP_NODELAY,
			(char *)&yes,
			sizeof(int)
	);
	setsockopt(
			sockfd,
			IPPROTO_TCP,
			SO_KEEPALIVE,
			(char *)&yes,
			sizeof(int)
	);
	setsockopt(
			sockfd,
			IPPROTO_TCP,
			SO_REUSEADDR,
			(char *)&yes,
			sizeof(int)
	);

	if (sockfd < 0) {
		ptp_verbose_log("Failed to create socket\n");
		return -1;
	}

	if (set_nonblocking_io(sockfd, 1) < 0) {
		close(sockfd);
		ptp_verbose_log("Failed to set non-blocking IO\n");
		return -1;
	}

	plat_dbg("Connecting to %s:%d", addr, port);

	struct sockaddr_in sa;
	memset(&sa, 0, sizeof(sa));
	sa.sin_family = AF_INET;
	sa.sin_port = htons(port);
	if (inet_pton(AF_INET, addr, &(sa.sin_addr)) <= 0) {
		close(sockfd);
		ptp_verbose_log("Failed to convert IP address\n");
		return -1;
	}

	errno = 0;
	if (connect(sockfd, (struct sockaddr*)&sa, sizeof(sa)) < 0) {
		if (errno != EINPROGRESS) {
			close(sockfd);
			ptp_verbose_log("Failed to connect to socket\n");
			return -1;
		}
	}

	plat_dbg("Errno: %d", errno);

	// timeout handling
	fd_set fdset;
	FD_ZERO(&fdset);
	FD_SET(sockfd, &fdset);
	struct timeval tv;
	tv.tv_sec = timeout_sec;
	tv.tv_usec = 0;

	plat_dbg("Waiting for select");
	rc = select(sockfd + 1, NULL, &fdset, NULL, &tv);
	plat_dbg("Select over");
	if (rc == 1) {
		int so_error = 0;
		socklen_t len = sizeof(so_error);
		if (getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &so_error, &len) < 0) {
			close(sockfd);
			ptp_verbose_log("Failed to get socket options\n");
			return -1;
		}

		if (so_error == 0) {
			ptp_verbose_log("Connection established %s:%d (%d)\n", addr, port, sockfd);
			set_nonblocking_io(sockfd, 0); // ????
			return sockfd;
		}
	}

	//if (errno == 115) return sockfd;

	close(sockfd);
	ptp_verbose_log("Failed to connect: %d, %d\n", rc, errno);
	return -1;
}

static struct PtpIpBackend *init_comm(struct PtpRuntime *r) {
	if (r->comm_backend == NULL) {
		r->comm_backend = calloc(1, sizeof(struct PtpIpBackend));
	}

	return (struct PtpIpBackend *)r->comm_backend;
}

int ptpip_connect(struct PtpRuntime *r, char *addr, int port) {
	int fd = ptpip_new_timeout_socket(addr, port, 1);

	struct PtpIpBackend *b = init_comm(r);

	if (fd > 0) {
		b->fd = fd;
		return 0;
	} else {
		b->fd = 0;
		return fd;
	}
}

JNI_FUNC(jint, cConnectNative)(JNIEnv *env, jobject thiz, jstring ip, jint port) {
	set_jni_env(env);
	const char *c_ip = (*env)->GetStringUTFChars(env, ip, 0);

	int rc = ptpip_connect(&backend.r, (char *)c_ip, (int)port);

	(*env)->ReleaseStringUTFChars(env, ip, c_ip);
	(*env)->DeleteLocalRef(env, ip);

	fuji_reset_ptp(&backend.r);

	return rc;
}

int ptpip_connect_events(struct PtpRuntime *r, char *addr, int port) {
	int fd = ptpip_new_timeout_socket(addr, port, 1);
	struct PtpIpBackend *b = init_comm(r);
	b->evfd = fd;
	// No error checking.
	// this can fail on blocking network (?) will return EINPROGRESS but socket is technically still valid and waiting to be connected
	return 0;
}

JNI_FUNC(jint, cConnectNativeEvents)(JNIEnv *env, jobject thiz, jstring ip, jint port) {
	set_jni_env(env);
	const char *c_ip = (*env)->GetStringUTFChars(env, ip, 0);

	int rc = ptpip_connect_events(&backend.r, (char *)c_ip, (int)port);

	(*env)->ReleaseStringUTFChars(env, ip, c_ip);
	(*env)->DeleteLocalRef(env, ip);

	return rc;
}

int ptpip_connect_video(struct PtpRuntime *r, char *addr, int port) {
	int fd = ptpip_new_timeout_socket(addr, port, 1);
	struct PtpIpBackend *b = init_comm(r);
	b->vidfd = fd;
	return 0;
}

JNI_FUNC(jint, cConnectVideoSocket)(JNIEnv *env, jobject thiz, jstring ip, jint port) {
	set_jni_env(env);
	const char *c_ip = (*env)->GetStringUTFChars(env, ip, 0);

	int rc = ptpip_connect_video(&backend.r, (char *)c_ip, (int)port);

	(*env)->ReleaseStringUTFChars(env, ip, c_ip);
	(*env)->DeleteLocalRef(env, ip);

	return rc;
}

int ptpip_close(struct PtpRuntime *r) {
	struct PtpIpBackend *b = init_comm(r);
	if (b->fd) close(b->fd);
	if (b->evfd) close(b->evfd);
	return 0;
}

int ptpip_cmd_write(struct PtpRuntime *r, void *data, int size) {
	if (r->io_kill_switch) return -1;
	struct PtpIpBackend *b = init_comm(r);
	int result = write(b->fd, data, size);
	if (result < 0) {
		return -1;
	} else {
		return result;
	}
}

int ptpip_cmd_read(struct PtpRuntime *r, void *data, int size) {
	if (r->io_kill_switch) return -1;
	struct PtpIpBackend *b = init_comm(r);
	int result = read(b->fd, data, size);
	if (result < 0) {
		return -1;
	} else {
		if (backend.progress_bar != NULL) {
			app_increment_progress_bar(result);
		}
		return result;
	}
}

int ptpip_event_send(struct PtpRuntime *r, void *data, int size) {
	if (r->io_kill_switch) return -1;
	struct PtpIpBackend *b = init_comm(r);
	int result = write(b->evfd, data, size);
	if (result < 0) {
		return -1;
	} else {
		return result;
	}
}

int ptpip_event_read(struct PtpRuntime *r, void *data, int size) {
	if (r->io_kill_switch) return -1;
	struct PtpIpBackend *b = init_comm(r);
	int result = read(b->evfd, data, size);
	if (result < 0) {
		return -1;
	} else {
		return result;
	}
}
