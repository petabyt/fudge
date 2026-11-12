#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <inttypes.h>
#include <stdlib.h>
#include <stdio.h>
//#define JPEG_LIB_VERSION 80
//typedef unsigned char JSAMPLE;
#include <jinclude.h>
#include <jpeglib.h>
#include <jerror.h>
#include "main.h"

METHODDEF(void)my_error_exit(j_common_ptr cinfo) {
	struct jpeg_error_mgr *err = cinfo->err;
	char buffer[1024];
	err->format_message(cinfo, buffer);
	__android_log_write(ANDROID_LOG_ERROR, "libjpeg", buffer);
}

static int is_jpeg(const uint8_t *buf) { return (buf[0] == 0xff && buf[1] == 0xd8); }

int liveview_render_frame(JNIEnv *env, struct SurfacePriv *priv, const void *buffer, unsigned int size) {
	ANativeWindow *window = priv->window;
	if (window == NULL) {
		__android_log_write(ANDROID_LOG_ERROR, "liveview", "surface handle is null");
		abort();
	}

	ANativeWindow_Buffer winbuf;
	if (is_jpeg(buffer)) {
		struct jpeg_decompress_struct cinfo;
		struct jpeg_error_mgr jerr;

		cinfo.err = jpeg_std_error(&jerr);
		jerr.error_exit = my_error_exit;
		jpeg_create_decompress(&cinfo);

		jpeg_mem_src(&cinfo, buffer, size);

		jpeg_read_header(&cinfo, TRUE);

		ANativeWindow_setBuffersGeometry(window, (int)cinfo.image_width, (int)cinfo.image_height, WINDOW_FORMAT_RGBA_8888);
		if (ANativeWindow_lock(window, &winbuf, NULL)) {
			__android_log_write(ANDROID_LOG_ERROR, "liveview", "failed to lock window");
			return 0;
		}

		uint8_t *framebuffer = winbuf.bits;

		cinfo.out_color_space = JCS_EXT_RGBA;

		unsigned int y = 0;
		unsigned int x = 0;
//		if (winbuf.stride < winbuf.height) {
//			// Scale image height to aspect ratio (hacky math, shouldn't overflow though)
//			//y = (screen_height / 2) - (cinfo.image_height * screen_stride / cinfo.image_width / 2);
//			cinfo.scale_num = screen_height;
//			cinfo.scale_denom = cinfo.image_height;
//		} else {
//			cinfo.scale_num = screen_stride;
//			cinfo.scale_denom = cinfo.image_width;
//
//			//x = (screen_stride / 2) - (cinfo.image_width);
//		}

		jpeg_start_decompress(&cinfo);

		while (cinfo.output_scanline < cinfo.output_height) {
			// Read scanlines directly into framebuffer
			unsigned char *buffer_array[1] = {
				framebuffer + (y * (winbuf.stride * 4)) + (x * 4)
			};
			jpeg_read_scanlines(&cinfo, (JSAMPARRAY)buffer_array, 1);
			if (y <= (winbuf.height - 2)) y++;
		}

		jpeg_finish_decompress(&cinfo);
		jpeg_destroy_decompress(&cinfo);
	} else {
		return -1;
	}

	if (ANativeWindow_unlockAndPost(window)) {
		__android_log_write(ANDROID_LOG_ERROR, "liveview", "failed to unlock window");
	}
	return 0;
}

//JNIEXPORT void JNICALL
//Java_dev_danielc_fudge_ModuleLiveviewModel_nativeSurfaceDestroyed(JNIEnv *env, jobject thiz,
//                                                                jobject holder) {
//	// TODO: implement surfaceDestroyed()
//}
//
//JNIEXPORT void JNICALL
//Java_dev_danielc_fudge_ModuleLiveviewModel_nativeSurfaceChanged(JNIEnv *env, jobject thiz,
//                                                              jobject holder, jint i2, jint width,
//                                                              jint height) {
//	// TODO: implement surfaceChanged()
//}
//
//JNIEXPORT void JNICALL
//Java_dev_danielc_fudge_ModuleLiveviewModel_nativeSurfaceCreated(JNIEnv *env, jobject thiz,
//                                                              jobject surface_holder) {
//	// TODO: implement surfaceCreated()
//}