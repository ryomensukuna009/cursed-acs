#include <jni.h>

JNIEXPORT jstring JNICALL
Java_com_example_offlinenative_MainActivity_getNativeString(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "Hello from Offline NDK Native C on ARM64!");
}
