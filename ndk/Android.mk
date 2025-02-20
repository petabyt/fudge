LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LUA := $(LOCAL_PATH)/../third_party/lua
LIBJPEG := $(LOCAL_PATH)/../third_party//android-libjpeg-turbo
LIB := $(LOCAL_PATH)/../lib
CAMLIB := $(LIB)/camlib

CAMLIB_CORE := $(addprefix $(CAMLIB)/src/,transport.c data.c enum_dump.c enums.c canon.c operations.c packet.c lib.c conv.c generic.c)
CAMLIB_CORE += $(addprefix $(CAMLIB)/src/,lua/lua-cjson/strbuf.c lua/lua-cjson/lua_cjson.c lua/lua.c)

LIBFUDGE_CORE := $(CAMLIB_CORE) $(addprefix $(LIB)/,fuji.c fuji_usb.c tester.c net.c data.c discovery.c exif.c object.c fuji_lua.c lua_runtime.c)
LIB_FILES := main.c backend.c scripts.c camlib.c usb.c liveview.c settings.c android.c

LOCAL_MODULE := fudge
LOCAL_CFLAGS := -D ANDROID -Wall -Wshadow -Wcast-qual -Wpedantic -Werror=incompatible-pointer-types -Werror=deprecated-declarations -g -Werror=return-type -Wextra -pedantic-errors
LOCAL_SRC_FILES := $(LIBFUDGE_CORE) $(LIB_FILES)
LOCAL_C_INCLUDES += $(LOCAL_PATH) $(CAMLIB)/src $(CAMLIB)/src/lua $(LIB) $(LUA)
LOCAL_LDLIBS += -llog -landroid -lEGL -lGLESv2 -lGLESv1_CM

LOCAL_CFLAGS += -DMEM_SRCDST_SUPPORTED

LOCAL_C_INCLUDES += $(LIBJPEG)/libjpeg-turbo-2.0.1 $(LIBJPEG)/include

LOCAL_STATIC_LIBRARIES += lua libjpeg-turbo

include $(BUILD_SHARED_LIBRARY)
# This has to be in the order of LOCAL_STATIC_LIBRARIES.. ugh
include $(LOCAL_PATH)/Lua.mk
include $(LIBJPEG)/Android.mk