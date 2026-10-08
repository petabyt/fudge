// LibUSB wrapper around Android android.hardware.usb APIs
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <string.h>
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#include <libusb.h>
#include "ndk.h"

struct libusb_context {
	jobject usbmanager;
};

struct libusb_device {
	struct libusb_context *ctx;
	jobject device;
};

struct libusb_device_handle {
	struct libusb_device *dev;
	jobject connection;
	int fd;
};

int libusb_init(libusb_context **ctx) {
	JNIEnv *env = get_jni_env();
	jobject jctx = get_jni_ctx();
	(*ctx) = malloc(sizeof(struct libusb_context));

	jclass ClassContext = (*env)->FindClass(env, "android/content/Context");
	jfieldID lid_USB_SERVICE = (*env)->GetStaticFieldID(env, ClassContext, "USB_SERVICE", "Ljava/lang/String;");
	jobject USB_SERVICE = (*env)->GetStaticObjectField(env, ClassContext, lid_USB_SERVICE);

	jmethodID get_sys_service_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "android/content/Context"), "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;");
	jobject usbmanager = (*env)->CallObjectMethod(env, jctx, get_sys_service_m, USB_SERVICE);
	(*ctx)->usbmanager = (*env)->NewGlobalRef(env, usbmanager);

	return 0;
}

void libusb_exit(libusb_context *ctx) {
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, ctx->usbmanager);
}

void libusb_set_debug(libusb_context *ctx, int level) {}
libusb_device *libusb_ref_device(libusb_device *dev) { return dev; }

void libusb_unref_device(libusb_device *dev) {
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, dev->device);
	free(dev);
}

int libusb_get_configuration(libusb_device_handle *dev_handle, int *config) {
	*config = 0;
	return 0;
}

ssize_t libusb_get_device_list(libusb_context *ctx, libusb_device ***list) {
	JNIEnv *env = get_jni_env();

	(*env)->PushLocalFrame(env, 100);

	jclass man_c = (*env)->FindClass(env, "android/hardware/usb/UsbManager");
	jmethodID get_dev_list_m = (*env)->GetMethodID(env, man_c, "getDeviceList", "()Ljava/util/HashMap;");
	jobject deviceList = (*env)->CallObjectMethod(env, ctx->usbmanager, get_dev_list_m);

	jclass hashmap_c = (*env)->FindClass(env, "java/util/HashMap");
	jmethodID size_m = (*env)->GetMethodID(env, hashmap_c, "size", "()I");
	int device_list_size = (*env)->CallIntMethod(env, deviceList, size_m);
	jmethodID values_m = (*env)->GetMethodID(env, hashmap_c, "values", "()Ljava/util/Collection;");
	jobject dev_list = (*env)->CallObjectMethod(env, deviceList, values_m);
	jclass collection_c = (*env)->FindClass(env, "java/util/Collection");
	jmethodID iterator_m = (*env)->GetMethodID(env, collection_c, "iterator", "()Ljava/util/Iterator;");
	jobject iterator = (*env)->CallObjectMethod(env, dev_list, iterator_m);
	jclass iterator_c = (*env)->FindClass(env, "java/util/Iterator");
	jmethodID has_next_m = (*env)->GetMethodID(env, iterator_c, "hasNext", "()Z" );
	jmethodID next_m = (*env)->GetMethodID(env, iterator_c, "next", "()Ljava/lang/Object;" );

	(*list) = malloc(sizeof(void *) * device_list_size);

	for (int i = 0; (*env)->CallBooleanMethod(env, iterator, has_next_m); i++) {
		printf("Probing valid device\n");
		jobject device = (*env)->CallObjectMethod(env, iterator, next_m);

		(*list)[i] = malloc(sizeof(libusb_device));
		(*list)[i]->device = (*env)->NewGlobalRef(env, device);
	}

	(*env)->PopLocalFrame(env, NULL);

	return (ssize_t)device_list_size;
}

int libusb_get_device_descriptor(libusb_device *dev, struct libusb_device_descriptor *desc) {
	JNIEnv *env = get_jni_env();
	jclass usb_dev_c = (*env)->FindClass(env, "android/hardware/usb/UsbDevice" );
	jmethodID get_vendor_id_m = (*env)->GetMethodID(env, usb_dev_c, "getVendorId", "()I" );
	jmethodID get_product_id_m = (*env)->GetMethodID(env, usb_dev_c, "getProductId", "()I" );

	desc->bLength = 24; // guessing
	desc->bDescriptorType = LIBUSB_DT_DEVICE;
	desc->bcdUSB = 0;
	desc->bDeviceClass = 0;
	desc->bDeviceSubClass = 0;
	desc->bDeviceProtocol = 0;
	desc->bMaxPacketSize0 = 0;
	desc->idVendor = (uint16_t)(*env)->CallIntMethod(env, dev->device, get_vendor_id_m);
	desc->idProduct = (uint16_t)(*env)->CallIntMethod(env, dev->device, get_product_id_m);
	desc->bcdDevice = 0;
	desc->iManufacturer = 0;
	desc->iProduct = 0;
	desc->iSerialNumber = 0;
	desc->bNumConfigurations = 1; // todo
	return 0;
}

int libusb_get_config_descriptor(libusb_device *dev, uint8_t config_index, struct libusb_config_descriptor **config) {
	JNIEnv *env = get_jni_env();
	jclass usb_dev_c = (*env)->FindClass(env, "android/hardware/usb/UsbDevice" );
	jclass usb_interf_c = (*env)->FindClass(env, "android/hardware/usb/UsbInterface" );
	jclass usb_endpoint_c = (*env)->FindClass(env, "android/hardware/usb/UsbEndpoint" );

	jmethodID get_endpoint_count_m = (*env)->GetMethodID(env, usb_interf_c, "getEndpointCount", "()I" );
	jmethodID get_endpoint_m = (*env)->GetMethodID(env, usb_interf_c, "getEndpoint", "(I)Landroid/hardware/usb/UsbEndpoint;" );

	jmethodID get_addr_m = (*env)->GetMethodID(env, usb_endpoint_c, "getAddress", "()I" );
	jmethodID get_type_m = (*env)->GetMethodID(env, usb_endpoint_c, "getType", "()I" );
	jmethodID get_dir_m = (*env)->GetMethodID(env, usb_endpoint_c, "getDirection", "()I" );

	jmethodID get_dev_class_m = (*env)->GetMethodID(env, usb_interf_c, "getInterfaceClass", "()I");
	jmethodID MethodgetInterfaceCount = (*env)->GetMethodID(env, usb_dev_c, "getInterfaceCount", "()I");
	jmethodID MethodgetInterface = (*env)->GetMethodID(env, usb_dev_c, "getInterface", "(I)Landroid/hardware/usb/UsbInterface;");

	struct libusb_interface *interface = malloc(sizeof(struct libusb_interface));

	(*config) = (struct libusb_config_descriptor *)malloc(sizeof(struct libusb_config_descriptor));
	(*config)->bLength = 0;
	(*config)->interface = interface;
	(*config)->bNumInterfaces = 1;

	interface->num_altsetting = (*env)->CallIntMethod(env, dev->device, MethodgetInterfaceCount);
	struct libusb_interface_descriptor *altsettings = malloc(sizeof(struct libusb_interface_descriptor) * interface->num_altsetting);
	for (int i = 0; i < interface->num_altsetting; i++) {
		jobject interf = (*env)->CallObjectMethod(env, dev->device, MethodgetInterface, i);
		struct libusb_interface_descriptor *altsetting = &altsettings[i];
		altsetting->bLength = 0;
		altsetting->bDescriptorType = 0;
		altsetting->bInterfaceNumber = 0;
		altsetting->bAlternateSetting = 0;
		altsetting->bInterfaceClass = (uint8_t)(*env)->CallIntMethod(env, interf, get_dev_class_m);
		altsetting->bInterfaceSubClass = 0;
		altsetting->bInterfaceProtocol = 0;
		altsetting->iInterface = 0;

		altsetting->bNumEndpoints = (uint8_t)(*env)->CallIntMethod(env, interf, get_endpoint_count_m);
		struct libusb_endpoint_descriptor *endpoints = malloc(sizeof(struct libusb_endpoint_descriptor) * altsetting->bNumEndpoints);
		for (uint8_t e = 0; e < altsetting->bNumEndpoints; e++) {
			struct libusb_endpoint_descriptor *ep = &endpoints[e];
			jobject ep_obj = (*env)->CallObjectMethod(env, interf, get_endpoint_m, e);
			ep->bLength = 0;
			ep->bDescriptorType = (uint8_t)(*env)->CallIntMethod(env, ep_obj, get_type_m);
			ep->bEndpointAddress = (uint8_t)(*env)->CallIntMethod(env, ep_obj, get_addr_m);
			ep->bmAttributes = (uint8_t)(*env)->CallIntMethod(env, ep_obj, get_dir_m);
			ep->wMaxPacketSize = 512;
			ep->bInterval = 0;
			ep->bRefresh = 0;
			ep->bSynchAddress = 0;
		}
		altsetting->endpoint = endpoints;
	}
	interface->altsetting = altsettings;

	return 0;
}

void libusb_free_config_descriptor(struct libusb_config_descriptor *config) {
	free((void *)config->interface->altsetting->endpoint);
	free((void *)config->interface->altsetting);
	free((void *)config->interface);
	free(config);
}

static int get_usb_permission(JNIEnv *env, jobject ctx, jobject man, jobject device) {
	(*env)->PushLocalFrame(env, 20);

	jclass man_c = (*env)->FindClass(env, "android/hardware/usb/UsbManager");

	jstring pkg = jni_get_package_name(env, ctx);
	jstring perm = jni_concat_strings2(env, pkg, (*env)->NewStringUTF(env, ".USB_PERMISSION"));

	jclass intent_c = (*env)->FindClass(env, "android/content/Intent");
	jmethodID intent_init = (*env)->GetMethodID(env, intent_c, "<init>", "(Ljava/lang/String;)V");
	jobject permission_intent = (*env)->NewObject(env, intent_c, intent_init, perm);

	jclass pending_intent_class = (*env)->FindClass(env, "android/app/PendingIntent");
	jmethodID pending_intent_get_broadcast = (*env)->GetStaticMethodID(env, pending_intent_class, "getBroadcast", "(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;");

	const jint FLAG_IMMUTABLE = 0x04000000;
	jobject pending_intent = (*env)->CallStaticObjectMethod(env, pending_intent_class, pending_intent_get_broadcast, ctx, 0, permission_intent, FLAG_IMMUTABLE);

	if (pending_intent == NULL) {
		(*env)->PopLocalFrame(env, NULL);
		return -1;
	}

	jmethodID req_perm_m = (*env)->GetMethodID(env, man_c, "requestPermission", "(Landroid/hardware/usb/UsbDevice;Landroid/app/PendingIntent;)V");
	(*env)->CallVoidMethod(env, man, req_perm_m, device, pending_intent);

	jmethodID has_perm_m = (*env)->GetMethodID(env, man_c, "hasPermission", "(Landroid/hardware/usb/UsbDevice;)Z");

	for (int i = 0; i < 10; i++) {
		// TODO: limit of 10 is kinda stupid. do something better
		if ((*env)->CallBooleanMethod(env, man, has_perm_m, device)) {
			(*env)->PopLocalFrame(env, NULL);
			return 0;
		}
		usleep(1000 * 1000);
	}

	(*env)->PopLocalFrame(env, NULL);
	return -1;
}

int libusb_open(libusb_device *dev, libusb_device_handle **dev_handle) {
	JNIEnv *env = get_jni_env();
	jobject ctx = get_jni_ctx();
	(*env)->PushLocalFrame(env, 20);

	jclass man_c = (*env)->FindClass(env, "android/hardware/usb/UsbManager");

	//get_usb_permission(env, ctx, man, dev->device);

	jmethodID open_dev_m = (*env)->GetMethodID(env, man_c, "openDevice", "(Landroid/hardware/usb/UsbDevice;)Landroid/hardware/usb/UsbDeviceConnection;");
	jobject connection = (*env)->CallObjectMethod(env, dev->ctx->usbmanager, open_dev_m, dev->device);
	if (connection == NULL) {
		(*env)->PopLocalFrame(env, NULL);
		return -1;
	}

	(*dev_handle) = (libusb_device_handle *)malloc(sizeof(struct libusb_device_handle));
	(*dev_handle)->dev = dev;
	(*dev_handle)->connection = (*env)->NewGlobalRef(env, connection);

	jmethodID get_desc_m = (*env)->GetMethodID(env, (*env)->FindClass(env, "android/hardware/usb/UsbDeviceConnection"), "getFileDescriptor", "()I");
	(*dev_handle)->fd = (*env)->CallIntMethod(env, connection, get_desc_m);

	(*env)->PopLocalFrame(env, NULL);
	return 0;
}

int libusb_get_string_descriptor_ascii(libusb_device_handle *dev, uint8_t desc_idx, unsigned char *data, int length) {
	strncpy((char *)data, "", length);
	return 0;
}

void libusb_free_device_list(libusb_device **list, int unref_devices) {

}

int libusb_set_auto_detach_kernel_driver(libusb_device_handle *dev_handle, int enable) {
	return 0;
}

int libusb_claim_interface(libusb_device_handle *dev_handle, int interface_number) {
	JNIEnv *env = get_jni_env();
	jclass usb_dev_c = (*env)->FindClass(env, "android/hardware/usb/UsbDevice");
	jmethodID MethodgetInterface = (*env)->GetMethodID(env, usb_dev_c, "getInterface", "(I)Landroid/hardware/usb/UsbInterface;");
	jobject interface = (*env)->CallObjectMethod(env, dev_handle->dev->device, MethodgetInterface, interface_number);

	jclass conn_c = (*env)->FindClass(env, "android/hardware/usb/UsbDeviceConnection");
	jmethodID release_interf_m = (*env)->GetMethodID(env, conn_c, "claimInterface", "(Landroid/hardware/usb/UsbInterface;Z)Z");
	(*env)->CallBooleanMethod(env, dev_handle->connection, release_interf_m, interface, 1);
	return 0;
}

void libusb_close(libusb_device_handle *dev_handle) {
	JNIEnv *env = get_jni_env();
	jclass class = (*env)->FindClass(env, "android/hardware/usb/UsbDeviceConnection");

	jmethodID close = (*env)->GetMethodID(env, class, "close", "()V");
	(*env)->CallVoidMethod(env, dev_handle->connection, close);
	(*env)->DeleteGlobalRef(env, dev_handle->connection);

	free(dev_handle);
}

int libusb_release_interface(libusb_device_handle *dev_handle, int interface_number) {
	JNIEnv *env = get_jni_env();
	jclass usb_dev_c = (*env)->FindClass(env, "android/hardware/usb/UsbDevice");
	jmethodID MethodgetInterface = (*env)->GetMethodID(env, usb_dev_c, "getInterface", "(I)Landroid/hardware/usb/UsbInterface;");
	jobject interface = (*env)->CallObjectMethod(env, dev_handle->dev->device, MethodgetInterface, interface_number);

	jclass conn_c = (*env)->FindClass(env, "android/hardware/usb/UsbDeviceConnection");
	jmethodID release_interf_m = (*env)->GetMethodID(env, conn_c, "releaseInterface", "(Landroid/hardware/usb/UsbInterface;)Z");
	(*env)->CallBooleanMethod(env, dev_handle->connection, release_interf_m, interface);
	return 0;
}

int libusb_bulk_transfer(libusb_device_handle *dev, unsigned char endpoint,
						 unsigned char *data, int length, int *transferred, unsigned int timeout) {
	struct usbdevfs_bulktransfer ctrl;
	ctrl.ep = endpoint;
	ctrl.len = length;
	ctrl.data = data;
	ctrl.timeout = timeout;
	return ioctl(dev->fd, USBDEVFS_BULK, &ctrl);
}

int libusb_control_transfer(libusb_device_handle *dev, uint8_t bmRequestType, uint8_t bRequest, uint16_t wValue, uint16_t wIndex, unsigned char *data, uint16_t wLength, unsigned int timeout) {
	printf("Not implemented yet\n");
	abort();
}

int libusb_get_max_packet_size(libusb_device *dev, unsigned char endpoint) {
	struct libusb_config_descriptor *config;
	int r = libusb_get_config_descriptor(dev, 0, &config);
	if (r < 0) return r;
	int ret = LIBUSB_ERROR_NOT_FOUND;
	for (int i = 0; i < config->interface[0].altsetting[0].bNumEndpoints; i++) {
		const struct libusb_endpoint_descriptor *ep = &config->interface[0].altsetting[0].endpoint[i];
		if (ep->bEndpointAddress == endpoint) {
			ret = ep->wMaxPacketSize;
			break;
		}
	}
	libusb_free_config_descriptor(config);
	return ret;
}

int libusb_set_interface_alt_setting(libusb_device_handle *dev_handle, int interface_number, int alternate_setting) {
	(void)dev_handle; (void)interface_number; (void)alternate_setting;
	return 0;
}

int libusb_clear_halt(libusb_device_handle *dev_handle, unsigned char endpoint) {
	(void)dev_handle; (void)endpoint;
	return 0;
}

int libusb_kernel_driver_active(libusb_device_handle *dev_handle, int interface_number) {
	(void)dev_handle; (void)interface_number;
	return 0;
}

int libusb_detach_kernel_driver(libusb_device_handle *dev_handle, int interface_number) {
	(void)dev_handle; (void)interface_number;
	return 0;
}

int libusb_interrupt_transfer(libusb_device_handle *dev, unsigned char endpoint, unsigned char *data, int length, int *transferred, unsigned int timeout) {
	return -1;
}