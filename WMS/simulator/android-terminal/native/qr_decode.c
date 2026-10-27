/* Offline QR image recognition, using the ISC-licensed quirc C library.
 * This entry point never changes the scan queue or starts network services. */
#include <jni.h>
#include <stdint.h>
#include "third_party/quirc/lib/quirc.h"

JNIEXPORT jbyteArray JNICALL Java_cn_projectb_terminal_CameraScanActivity_nativeDecodeQr(
        JNIEnv *env, jclass cls, jbyteArray luma, jint width, jint height) {
    (void)cls;
    if (!luma || width < 32 || height < 32 || width > 1920 || height > 1920)
        return NULL;
    size_t pixels = (size_t)width * (size_t)height;
    if (pixels > 1920U * 1080U || (size_t)(*env)->GetArrayLength(env, luma) < pixels)
        return NULL;
    struct quirc *q = quirc_new();
    if (!q) return NULL;
    if (quirc_resize(q, width, height)) {
        quirc_destroy(q);
        return NULL;
    }
    uint8_t *image = quirc_begin(q, NULL, NULL);
    (*env)->GetByteArrayRegion(env, luma, 0, (jsize)pixels, (jbyte *)image);
    if ((*env)->ExceptionCheck(env)) {
        quirc_destroy(q);
        return NULL;
    }
    quirc_end(q);
    jbyteArray result = NULL;
    for (int i = 0; i < quirc_count(q); i++) {
        struct quirc_code code;
        struct quirc_data data;
        quirc_extract(q, i, &code);
        quirc_decode_error_t error = quirc_decode(&code, &data);
        if (error) {
            quirc_flip(&code);
            error = quirc_decode(&code, &data);
        }
        if (error || data.payload_len < 1 || data.payload_len > QUIRC_MAX_PAYLOAD)
            continue;
        result = (*env)->NewByteArray(env, data.payload_len);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, data.payload_len, (jbyte *)data.payload);
        break;
    }
    quirc_destroy(q);
    return result;
}
