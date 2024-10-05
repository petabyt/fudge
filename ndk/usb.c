#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <string.h>
#include <camlib.h>
#include "backend.h"
#include "app.h"

// TODO: Use comm_backend?
static struct PrivUSB {
	jobject obj;
	int fd;
	int endpoint_in;
	int endpoint_out;
}usb_priv;

int jni_setup_usb(JNIEnv *env, jobject obj) {
	usb_priv.obj = (*env)->NewGlobalRef(env, obj);

	jclass class = (*env)->GetObjectClass(env, obj);
	jmethodID get_fd = (*env)->GetMethodID(env, class, "getFileDescriptor", "()I");
	jint fd = (*env)->CallIntMethod(env, obj, get_fd);
	usb_priv.fd = (int)fd;

	// Get endpoints - TODO: get interrupt endpoint
	jfieldID e_out = (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/common/SimpleUSB"), "endpointOut", "Landroid/hardware/usb/UsbEndpoint;");
	jfieldID e_in = (*env)->GetFieldID(env, (*env)->FindClass(env, "dev/danielc/common/SimpleUSB"), "endpointIn", "Landroid/hardware/usb/UsbEndpoint;");
	jobject out = (*env)->GetObjectField(env, obj, e_out);
	jobject in = (*env)->GetObjectField(env, obj, e_in);
	jmethodID get_address = (*env)->GetMethodID(env, (*env)->FindClass(env, "android/hardware/usb/UsbEndpoint"), "getAddress", "()I");
	usb_priv.endpoint_out = (*env)->CallIntMethod(env, out, get_address);
	usb_priv.endpoint_in = (*env)->CallIntMethod(env, in, get_address);

	plat_dbg("USB Endpoints: %d< >%d", usb_priv.endpoint_in, usb_priv.endpoint_out);

	return 0;
}

JNI_FUNC(jint, cUSBConnectNative)(JNIEnv *env, jclass thiz, jobject usb) {
	set_jni_env(env);

	jni_setup_usb(env, usb);

	ptp_reset(&backend.r);
	backend.r.connection_type = PTP_USB;
	backend.r.max_packet_size = 512; // Android can go higher, but this will do

	return 0;
}

int ptp_device_init(struct PtpRuntime *r) {
	// connection done by frontend
	return -1;
}

int ptp_cmd_write(struct PtpRuntime *r, void *to, int length) {
	if (r->io_kill_switch) return -1;
	struct usbdevfs_bulktransfer ctrl;
	ctrl.ep = usb_priv.endpoint_out;
	ctrl.len = length;
	ctrl.data = to;
	ctrl.timeout = PTP_TIMEOUT;
	return ioctl(usb_priv.fd, USBDEVFS_BULK, &ctrl);
}

int ptp_cmd_read(struct PtpRuntime *r, void *to, int length) {
	if (r->io_kill_switch) return -1;
	struct usbdevfs_bulktransfer ctrl;
	ctrl.ep = usb_priv.endpoint_in;
	ctrl.len = length;
	ctrl.data = to;
	ctrl.timeout = PTP_TIMEOUT;
	int rc = ioctl(usb_priv.fd, USBDEVFS_BULK, &ctrl);

	if (rc > 0) app_increment_progress_bar(rc);

	return rc;
}

int ptp_device_close(struct PtpRuntime *r) {
	JNIEnv *env = get_jni_env();
	jclass class = (*env)->GetObjectClass(env, usb_priv.obj);
	jmethodID close = (*env)->GetMethodID(env, class, "closeConnection", "()V");
	(*env)->CallVoidMethod(env, usb_priv.obj, close);
	(*env)->DeleteGlobalRef(env, usb_priv.obj);
	return 0;
}

int ptp_device_reset(struct PtpRuntime *r) {
	return -1;
}

int ptp_read_int(struct PtpRuntime *r, void *to, int length) {
	return -1;
}

int reset_int(void) {
	return -1;
}
