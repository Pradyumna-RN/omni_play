#include <jni.h>
#include "openjoc.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

namespace {
struct Decoder {
  openjoc_stream_decoder* decoder = nullptr;
  std::vector<float> pcm;
  std::string error;
  uint32_t rate = 0;
  int64_t firstPts = 0;
  int64_t producedFrames = 0;
  size_t latency = 0;
  ~Decoder() { if (decoder) openjoc_stream_decoder_destroy(decoder); }
};
Decoder* ptr(jlong value) { return reinterpret_cast<Decoder*>(static_cast<uintptr_t>(value)); }
int fail(Decoder* d, const char* why) { d->error = why ? std::string(why).substr(0, 256) : "OpenJOC decode failed"; return -1; }
int receive(Decoder* d) {
  openjoc_pcm_frame f{};openjoc_pcm_frame_init(&f);
  while (true) {
    const auto s = openjoc_stream_decoder_receive_frame(d->decoder, &f);
    if (s == OPENJOC_STATUS_NEED_MORE_INPUT || s == OPENJOC_STATUS_END_OF_STREAM || s == OPENJOC_STATUS_OK) return 0;
    if (s != OPENJOC_STATUS_FRAME_AVAILABLE) return fail(d, openjoc_stream_decoder_last_error(d->decoder));
    if (f.sample_format != 1 || f.channel_count != 12 || f.sample_rate != 48000 ||
        f.sample_count > 1048576 || f.data_len != f.sample_count * 12 * sizeof(float) || !f.data)
      return fail(d, "OpenJOC returned an unsupported PCM format");
    if (d->pcm.empty()) d->firstPts = d->producedFrames;
    d->producedFrames += static_cast<int64_t>(f.sample_count);
    // Keep the channel order explicit. Never reinterpret wide channels as rear centres.
    static const char* labels[] = {"FL","FR","FC","LFE","BL","BR","SL","SR","TFL","TFR","TBL","TBR"};
    if (d->rate == 0) {
      for (size_t i=0;i<12;i++) {
        const auto label = openjoc_stream_decoder_get_channel_label(d->decoder,i);
        if (!label || std::strcmp(label,labels[i])) return fail(d,"Unexpected OpenJOC speaker order");
      }
      d->rate = f.sample_rate;
      openjoc_output_info info{};openjoc_output_info_init(&info);
      if (openjoc_stream_decoder_get_output_info(d->decoder,&info)==OPENJOC_STATUS_OK) d->latency=info.latency_samples;
    }
    const size_t count = f.data_len / sizeof(float);
    if (d->pcm.size() > 4194304 || count > 4194304-d->pcm.size()) return fail(d,"OpenJOC output block is too large");
    d->pcm.insert(d->pcm.end(),f.data,f.data+count);
  }
}
}
extern "C" {
JNIEXPORT jint JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocLibrary_abiVersion(JNIEnv*,jclass) { return static_cast<jint>(openjoc_get_abi_version()); }
JNIEXPORT jlong JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeCreate(JNIEnv*,jclass) {
  try {
    auto d=std::make_unique<Decoder>();openjoc_decoder_config c{};
    if (openjoc_decoder_config_init_v1_7(&c)!=OPENJOC_STATUS_OK) return 0;
    c.render_mode=OPENJOC_RENDER_SPEAKER;c.speaker_layout="7.1.4";c.drc=OPENJOC_DRC_DISABLED;
    if (openjoc_stream_decoder_create(&c,&d->decoder)!=OPENJOC_STATUS_OK) return 0;
    d->pcm.reserve(12*1536*6);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(d.release()));
  } catch (...) {return 0;}
}
JNIEXPORT jint JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeDecode(JNIEnv* env,jclass,jlong handle,jobject input,jint offset,jint length,jboolean eos) {
  auto* d=ptr(handle);if(!d)return -1;
  try {
    d->error.clear();d->pcm.clear();
    if (eos) {
      const auto s=openjoc_stream_decoder_drain(d->decoder);
      if(s!=OPENJOC_STATUS_OK && s!=OPENJOC_STATUS_FRAME_AVAILABLE && s!=OPENJOC_STATUS_END_OF_STREAM && s!=OPENJOC_STATUS_NEED_MORE_INPUT && s!=OPENJOC_STATUS_OUTPUT_PENDING)
        return fail(d,openjoc_stream_decoder_last_error(d->decoder));
      if(receive(d)<0)return -1;
    } else {
      auto* data=static_cast<uint8_t*>(env->GetDirectBufferAddress(input));
      auto capacity=env->GetDirectBufferCapacity(input);
      if(!data || offset<0 || length<0 || length>1048576 || capacity<static_cast<jlong>(offset)+length)return fail(d,"Invalid compressed input buffer");
      for(unsigned retries=0;;retries++) {
        if(retries>1024)return fail(d,"OpenJOC did not consume the input block");
        // In pinned ABI 1.7, OUTPUT_PENDING is returned before input is staged.
        // Drain queued output, then retry these exact unconsumed bytes. The
        // host backpressure probe verifies this against the real C API.
        const auto s=openjoc_stream_decoder_send_chunk(d->decoder,data+offset,static_cast<size_t>(length),OPENJOC_NO_PTS,0);
        if(s!=OPENJOC_STATUS_OK && s!=OPENJOC_STATUS_NEED_MORE_INPUT && s!=OPENJOC_STATUS_FRAME_AVAILABLE && s!=OPENJOC_STATUS_OUTPUT_PENDING)
          return fail(d,openjoc_stream_decoder_last_error(d->decoder));
        const auto before=d->pcm.size();
        if(receive(d)<0)return -1;
        if(s!=OPENJOC_STATUS_OUTPUT_PENDING)break;
        if(d->pcm.size()==before)return fail(d,"OpenJOC backpressure made no progress");
      }
    }
    return static_cast<jint>(d->pcm.size()*sizeof(float));
  } catch (...) {return fail(d,"OpenJOC native processing failed");}
}
JNIEXPORT jint JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeCopy(JNIEnv* env,jclass,jlong handle,jobject output) {
  auto* d=ptr(handle);if(!d)return -1;
  auto* dst=env->GetDirectBufferAddress(output);const auto capacity=env->GetDirectBufferCapacity(output);
  const auto bytes=d->pcm.size()*sizeof(float);
  if(!dst || capacity<static_cast<jlong>(bytes))return -1;
  if(bytes)std::memcpy(dst,d->pcm.data(),bytes);
  return 0;
}
JNIEXPORT jlong JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeFirstPts(JNIEnv*,jclass,jlong h) {auto*d=ptr(h);return d?d->firstPts:0;}
JNIEXPORT jint JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeLatency(JNIEnv*,jclass,jlong h) {auto*d=ptr(h);return d?static_cast<jint>(d->latency):0;}
JNIEXPORT jstring JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeError(JNIEnv* e,jclass,jlong h) {auto*d=ptr(h);return e->NewStringUTF(d?d->error.c_str():"Decoder is closed");}
JNIEXPORT jint JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeReset(JNIEnv*,jclass,jlong h) {
  auto*d=ptr(h);if(!d)return -1;d->pcm.clear();d->error.clear();d->rate=0;d->latency=0;d->firstPts=0;d->producedFrames=0;
  return openjoc_stream_decoder_reset(d->decoder)==OPENJOC_STATUS_OK?0:-1;
}
JNIEXPORT void JNICALL Java_labs_rtensor_decoder_openjoc_OpenJocAudioDecoder_nativeDestroy(JNIEnv*,jclass,jlong h) {delete ptr(h);}
}
