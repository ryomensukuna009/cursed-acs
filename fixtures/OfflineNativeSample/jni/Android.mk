LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := offlinetest
LOCAL_SRC_FILES := native_code.c
include $(BUILD_SHARED_LIBRARY)
