/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <android/log.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                                   \
  extern "C" {                                                                 \
  JNIEXPORT RETURN_TYPE                                                        \
      Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(JNIEnv *env,    \
                                                               jobject thiz,   \
                                                               ##__VA_ARGS__); \
  }                                                                            \
  JNIEXPORT RETURN_TYPE                                                        \
      Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(                \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)                   \
  extern "C" {                                                       \
  JNIEXPORT RETURN_TYPE                                              \
      Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                                  \
  JNIEXPORT RETURN_TYPE                                              \
      Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec *getCodecByName(JNIEnv *env, jstring codecName);

/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
AVCodecContext *createContext(JNIEnv *env, const AVCodec *codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount);

struct GrowOutputBufferCallback {
  uint8_t *operator()(int requiredSize) const;

  JNIEnv *env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error. One packet may hold several codec frames (for example an MP4 E-AC-3
 * sample made of several syncframes); their PCM is appended contiguously.
 * {@code growBuffer(requiredSize)} must return the base address of a buffer of
 * at least that size that preserves the bytes already written.
 */
template <typename GrowBuffer>
int decodePacket(AVCodecContext *context, AVPacket *packet,
                 uint8_t *outputBuffer, int outputSize,
                 const GrowBuffer &growBuffer);

/**
 * Returns a resampler for this frame's own layout, rate and sample format.
 * The cached converter is rebuilt when any of them changes mid-stream, so it
 * never reinterprets new PCM with an earlier packet's layout.
 */
SwrContext *resamplerFor(AVCodecContext *context, const AVFrame *frame);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char *functionName, int errorNumber);

/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext *context);

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  return JNI_VERSION_1_6;
}

LIBRARY_FUNC(jstring, ffmpegGetVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

LIBRARY_FUNC(jint, ffmpegGetInputBufferPaddingSize) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

LIBRARY_FUNC(jboolean, ffmpegHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

AUDIO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData, jboolean outputFloat,
                   jint rawSampleRate, jint rawChannelCount) {
  const AVCodec *codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, outputFloat, rawSampleRate,
                              rawChannelCount);
}

AUDIO_DECODER_FUNC(jint, ffmpegDecode, jlong context, jobject inputData,
                   jint inputSize, jobject decoderOutputBuffer,
                   jobject outputData, jint outputSize) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t *inputBuffer = (uint8_t *)env->GetDirectBufferAddress(inputData);
  uint8_t *outputBuffer = (uint8_t *)env->GetDirectBufferAddress(outputData);
  // FFmpeg's bit readers may read beyond the packet into zero padding. Input
  // buffers are reused, so an earlier, larger packet can leave nonzero bytes.
  const jlong inputCapacity = env->GetDirectBufferCapacity(inputData);
  const jlong outputCapacity = env->GetDirectBufferCapacity(outputData);
  if (!inputBuffer || !outputBuffer ||
      inputCapacity < (jlong)inputSize + AV_INPUT_BUFFER_PADDING_SIZE ||
      outputCapacity < outputSize) {
    LOGE("Invalid direct-buffer capacity.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  memset(inputBuffer + inputSize, 0, AV_INPUT_BUFFER_PADDING_SIZE);
  AVPacket *packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((AVCodecContext *)context, packet, outputBuffer, outputSize,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t *GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t *>(env->GetDirectBufferAddress(newOutputData));
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext *)context)->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext *)context)->sample_rate;
}

// RTensorLabs: native masks describe FFmpeg's canonical interleaved order.
// Unknown/custom/ambisonic layouts must not be guessed from the channel count.
AUDIO_DECODER_FUNC(jlong, ffmpegGetChannelLayoutMask, jlong context) {
  if (!context) return 0;
  const AVChannelLayout &layout = ((AVCodecContext *)context)->ch_layout;
  return layout.order == AV_CHANNEL_ORDER_NATIVE ? (jlong)layout.u.mask : 0;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  AVCodecContext *context = (AVCodecContext *)jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    // Cache before release (upstream otherwise read the freed context).
    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
    // Release and recreate the context if the codec is TrueHD.
    // TODO: Figure out why flushing doesn't work for this codec.
    releaseContext(context);
    const AVCodec *codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    return (jlong)createContext(env, codec, extraData, outputFloat,
                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
  }

  avcodec_flush_buffers(context);
  return (jlong)context;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((AVCodecContext *)context);
  }
}

const AVCodec *getCodecByName(JNIEnv *env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char *codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec *codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext *createContext(JNIEnv *env, const AVCodec *codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount) {
  AVCodecContext *context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return NULL;
  }
  context->request_sample_fmt =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    context->extradata =
        (uint8_t *)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(context);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte *)context->extradata);
  }
  if (context->codec_id == AV_CODEC_ID_PCM_MULAW ||
      context->codec_id == AV_CODEC_ID_PCM_ALAW) {
    context->sample_rate = rawSampleRate;
    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
  context->err_recognition = AV_EF_IGNORE_ERR;
  // Preserve full-range decoded audio; no codec-side DRC or forced stereo mix.
  if (context->priv_data) av_opt_set_double(context->priv_data, "drc_scale", 0.0, 0);
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(context);
    return NULL;
  }
  return context;
}

SwrContext *resamplerFor(AVCodecContext *context, const AVFrame *frame) {
  SwrContext *resampleContext = static_cast<SwrContext *>(context->opaque);
  if (resampleContext) {
    int64_t rate = 0, format = AV_SAMPLE_FMT_NONE;
    AVChannelLayout layout = {};
    bool same = av_opt_get_int(resampleContext, "in_sample_rate", 0, &rate) >= 0 &&
                av_opt_get_int(resampleContext, "in_sample_fmt", 0, &format) >= 0 &&
                av_opt_get_chlayout(resampleContext, "in_chlayout", 0, &layout) >= 0 &&
                rate == frame->sample_rate && format == frame->format &&
                av_channel_layout_compare(&layout, &frame->ch_layout) == 0;
    av_channel_layout_uninit(&layout);
    if (same) return resampleContext;
    LOGD("Decoded layout, rate or format changed; rebuilding the converter.");
    swr_free(&resampleContext);
    context->opaque = NULL;
  }
  int result = swr_alloc_set_opts2(
      &resampleContext, &frame->ch_layout, context->request_sample_fmt,
      frame->sample_rate, &frame->ch_layout, (AVSampleFormat)frame->format,
      frame->sample_rate, 0, NULL);
  if (result < 0) {
    logError("swr_alloc_set_opts2", result);
    swr_free(&resampleContext);
    return NULL;
  }
  result = swr_init(resampleContext);
  if (result < 0) {
    logError("swr_init", result);
    swr_free(&resampleContext);
    return NULL;
  }
  context->opaque = resampleContext;
  return resampleContext;
}

template <typename GrowBuffer>
int decodePacket(AVCodecContext *context, AVPacket *packet,
                 uint8_t *outputBuffer, int outputSize,
                 const GrowBuffer &growBuffer) {
  int result = avcodec_send_packet(context, packet);
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  // RTensorLabs: outputBuffer always stays the base address; frames are
  // written at base + outSize. Upstream advanced the pointer and the earlier
  // local patch also added outSize, so the second frame of a packet landed
  // twice as far in (leaving stale bytes and overrunning the buffer).
  int outSize = 0;
  int packetChannels = 0;
  while (true) {
    AVFrame *frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_OTHER;
    }
    result = avcodec_receive_frame(context, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) break;
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }
    SwrContext *resampleContext = resamplerFor(context, frame);
    if (!resampleContext) {
      av_frame_free(&frame);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    // A packet's frames must share one layout: the caller reports one PCM
    // format per output buffer.
    if (packetChannels && frame->ch_layout.nb_channels != packetChannels) {
      LOGE("Channel count changed inside one packet.");
      av_frame_free(&frame);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    int channelCount = packetChannels = frame->ch_layout.nb_channels;
    int outSampleSize = av_get_bytes_per_sample(context->request_sample_fmt);
    int frameBytes = outSampleSize * channelCount;
    int outSamples = swr_get_out_samples(resampleContext, frame->nb_samples);
    if (channelCount <= 0 || outSamples < 0) {
      av_frame_free(&frame);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    int required = outSize + frameBytes * outSamples;
    if (required > outputSize) {
      LOGD("Output buffer size (%d) too small for output data (%d), "
           "reallocating buffer.", outputSize, required);
      outputSize = required;
      outputBuffer = growBuffer(outputSize);
      if (!outputBuffer) {
        LOGE("Failed to reallocate output buffer.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
    }
    uint8_t *outputPlanes[] = {outputBuffer + outSize};
    // swr_convert's capacity is in frames (samples per channel), not bytes.
    int capacityFrames = (outputSize - outSize) / frameBytes;
    result = swr_convert(resampleContext, outputPlanes, capacityFrames,
                         (const uint8_t **)frame->extended_data,
                         frame->nb_samples);
    av_frame_free(&frame);
    if (result < 0) {
      logError("swr_convert", result);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    int available = swr_get_out_samples(resampleContext, 0);
    if (available != 0) {
      LOGE("Expected no samples remaining after resampling, but found %d.",
           available);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    // Count what was written, not the converter's upper bound.
    outSize += result * frameBytes;
  }
  return outSize;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char *functionName, int errorNumber) {
  char *buffer = (char *)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext *context) {
  if (!context) {
    return;
  }
  SwrContext *swrContext;
  if ((swrContext = (SwrContext *)context->opaque)) {
    swr_free(&swrContext);
    context->opaque = NULL;
  }
  avcodec_free_context(&context);
}
