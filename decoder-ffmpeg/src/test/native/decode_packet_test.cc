// Host regression tests for the JNI decode loop (no JVM or device needed).
// Built and run by decoder-ffmpeg/tools/native_test.py with AddressSanitizer.
// The adapter is included directly so decodePacket/createContext are tested
// exactly as shipped; only the output-buffer growth callback is replaced.
#include "../../main/jni/ffmpeg_jni.cc"

#include <cmath>
#include <cstdio>
#include <fstream>
#include <functional>
#include <iterator>
#include <memory>
#include <string>
#include <vector>
extern "C" {
#include <libavutil/sha.h>
}

namespace {
int failures = 0;
#define EXPECT(condition, ...)                                   \
  do {                                                           \
    if (!(condition)) {                                          \
      failures++;                                                \
      fprintf(stderr, "FAIL %s:%d: %s\n  ", __FILE__, __LINE__, #condition); \
      fprintf(stderr, __VA_ARGS__);                              \
      fputc('\n', stderr);                                       \
    }                                                            \
  } while (0)

using Bytes = std::vector<uint8_t>;
constexpr uint8_t kFill = 0xA5;  // Recognisable bytes the decoder must overwrite.

std::vector<Bytes> syncframes(const std::string &path) {
  std::ifstream file(path, std::ios::binary);
  Bytes bytes((std::istreambuf_iterator<char>(file)), {});
  std::vector<Bytes> frames;
  size_t offset = 0;
  while (offset + 4 <= bytes.size()) {
    if (bytes[offset] != 0x0b || bytes[offset + 1] != 0x77) {
      fprintf(stderr, "%s: missing syncword at %zu\n", path.c_str(), offset);
      exit(2);
    }
    size_t size = 2 * ((((bytes[offset + 2] & 7) << 8) | bytes[offset + 3]) + 1);
    frames.emplace_back(bytes.begin() + offset, bytes.begin() + offset + size);
    offset += size;
  }
  return frames;
}

Bytes join(const std::vector<Bytes> &frames, size_t from, size_t count) {
  Bytes packet;
  for (size_t i = from; i < from + count; i++)
    packet.insert(packet.end(), frames[i].begin(), frames[i].end());
  return packet;
}

struct Decoder {
  AVCodecContext *context;
  explicit Decoder(bool outputFloat)
      : context(createContext(nullptr, avcodec_find_decoder_by_name("eac3"),
                              nullptr, outputFloat, -1, -1)) {
    if (!context) {
      fprintf(stderr, "eac3 decoder unavailable\n");
      exit(2);
    }
  }
  ~Decoder() { releaseContext(context); }

  // Mirrors SimpleDecoderOutputBuffer.grow(): a new allocation that keeps the
  // bytes already written and returns its base address.
  int decode(const Bytes &input, size_t capacity, Bytes &output, int *grows = nullptr) {
    Bytes padded(input);
    padded.resize(input.size() + AV_INPUT_BUFFER_PADDING_SIZE, 0);
    auto storage = std::make_unique<Bytes>(capacity, kFill);
    auto grow = [&](int required) -> uint8_t * {
      if (grows) (*grows)++;
      auto bigger = std::make_unique<Bytes>(required, kFill);
      std::copy(storage->begin(), storage->end(), bigger->begin());
      storage = std::move(bigger);
      return storage->data();
    };
    AVPacket *packet = av_packet_alloc();
    packet->data = padded.data();
    packet->size = (int)input.size();
    int result = decodePacket(context, packet, storage->data(), (int)capacity, grow);
    av_packet_free(&packet);
    output.assign(storage->begin(), storage->begin() + (result > 0 ? result : 0));
    return result;
  }
};

Bytes decodeSeparately(const std::vector<Bytes> &frames, size_t from, size_t count, bool outputFloat) {
  Decoder decoder(outputFloat);
  Bytes all, one;
  for (size_t i = 0; i < from + count; i++) {
    int written = decoder.decode(frames[i], 1 << 20, one);
    if (written < 0) return {};
    if (i >= from) all.insert(all.end(), one.begin(), one.end());
  }
  return all;
}

Bytes decodeJoined(const std::vector<Bytes> &frames, size_t from, size_t count, bool outputFloat,
                   size_t capacity, int *grows = nullptr) {
  Decoder decoder(outputFloat);
  Bytes out;
  for (size_t i = 0; i < from; i++) decoder.decode(frames[i], 1 << 20, out);
  int written = decoder.decode(join(frames, from, count), capacity, out, grows);
  return written < 0 ? Bytes() : out;
}

void oneSyncframePerPacketKeepsSixDistinctFiniteChannels(const std::vector<Bytes> &frames) {
  Decoder decoder(true);
  Bytes pcm;
  int written = decoder.decode(frames[4], 1 << 20, pcm);
  EXPECT(written == 1536 * 6 * 4, "wrote %d bytes", written);
  EXPECT(decoder.context->ch_layout.order == AV_CHANNEL_ORDER_NATIVE &&
             decoder.context->ch_layout.u.mask == 0x60f,
         "mask %llx", (unsigned long long)decoder.context->ch_layout.u.mask);
  const float *samples = reinterpret_cast<const float *>(pcm.data());
  double energy[6] = {};
  for (int frame = 0; frame < 1536; frame++)
    for (int channel = 0; channel < 6; channel++) {
      float sample = samples[frame * 6 + channel];
      EXPECT(std::isfinite(sample) && std::fabs(sample) <= 1.5f, "sample %f", sample);
      energy[channel] += sample * sample;
    }
  for (int channel = 0; channel < 6; channel++)
    EXPECT(energy[channel] > 1e-3, "channel %d is silent", channel);
}

void severalSyncframesInOnePacketAreContiguous(const std::vector<Bytes> &frames, bool outputFloat) {
  for (size_t count : {2u, 3u, 6u}) {
    Bytes expected = decodeSeparately(frames, 3, count, outputFloat);
    Bytes actual = decodeJoined(frames, 3, count, outputFloat, 1 << 20);
    size_t bytesPerSample = outputFloat ? 4 : 2;
    EXPECT(expected.size() == 1536 * 6 * bytesPerSample * count, "expected %zu", expected.size());
    EXPECT(actual == expected, "%zu-frame packet (%s): %zu bytes vs %zu, first difference at %zd",
           count, outputFloat ? "float" : "s16", actual.size(), expected.size(),
           [&] {
             for (size_t i = 0; i < std::min(actual.size(), expected.size()); i++)
               if (actual[i] != expected[i]) return (ssize_t)i;
             return (ssize_t)-1;
           }());
  }
}

void growingTheOutputBufferKeepsEarlierFrames(const std::vector<Bytes> &frames) {
  Bytes expected = decodeSeparately(frames, 3, 3, true);
  for (size_t capacity : {size_t(0), size_t(4096), size_t(1536 * 6 * 4), size_t(1536 * 6 * 4 + 7)}) {
    int grows = 0;
    Bytes actual = decodeJoined(frames, 3, 3, true, capacity, &grows);
    EXPECT(grows >= 1, "capacity %zu should have grown", capacity);
    EXPECT(actual == expected, "capacity %zu: grown output differs", capacity);
  }
}

void exactCapacityNeverWritesPastTheBuffer(const std::vector<Bytes> &frames) {
  // AddressSanitizer reports any overrun of the exactly-sized allocation.
  int grows = 0;
  Bytes actual = decodeJoined(frames, 3, 4, true, 1536 * 6 * 4 * 4, &grows);
  EXPECT(grows == 0, "an exact buffer should not grow (%d)", grows);
  EXPECT(actual.size() == 1536 * 6 * 4 * 4, "wrote %zu", actual.size());
}

void aLayoutChangeRebuildsTheConverter(const std::vector<Bytes> &surround, const std::vector<Bytes> &stereo) {
  Decoder decoder(true);
  Bytes out;
  for (int i = 0; i < 3; i++) decoder.decode(surround[i], 1 << 20, out);
  EXPECT(decoder.context->ch_layout.nb_channels == 6, "surround first");
  Bytes changed;
  for (int i = 0; i < 3; i++) decoder.decode(stereo[i], 1 << 20, changed);
  EXPECT(decoder.context->ch_layout.nb_channels == 2, "now %d channels", decoder.context->ch_layout.nb_channels);
  EXPECT(changed.size() == 1536 * 2 * 4, "stereo packet wrote %zu bytes", changed.size());
  Bytes expected = decodeSeparately(stereo, 2, 1, true);
  // Codec state (dynamic range/overlap history) may differ by rounding after
  // a switch; a converter still using the 5.1 layout would scramble the PCM.
  const float *a = reinterpret_cast<const float *>(changed.data());
  const float *b = reinterpret_cast<const float *>(expected.data());
  double worst = expected.size() == changed.size() ? 0 : 1e9, energy = 0;
  for (size_t i = 0; i < std::min(changed.size(), expected.size()) / 4; i++) {
    worst = std::max(worst, (double)std::fabs(a[i] - b[i]));
    energy += b[i] * b[i];
  }
  EXPECT(energy > 1e-3 && worst < 1e-3, "stereo PCM after a 5.1 stream differs by %g", worst);
}

void invalidDataIsReportedWithoutOutput() {
  Decoder decoder(true);
  Bytes garbage(512, 0x11), out;
  int result = decoder.decode(garbage, 1 << 20, out);
  EXPECT(result <= 0, "garbage produced %d bytes", result);
}

// Optional user-supplied clear recording: stream hashes avoid retaining minutes
// of decoded PCM in memory. No commercial recording is bundled with the test.
void completeRecordingMatchesAcrossPacketGroups(const std::string &path) {
  auto frames = syncframes(path);
  EXPECT(!frames.empty(), "recording contains no frames");
  std::vector<uint8_t> reference;
  int64_t referenceBytes = 0;
  for (size_t group : {1u, 2u, 3u, 6u}) {
    Decoder decoder(true);
    AVSHA *hash = av_sha_alloc();
    if (!hash || av_sha_init(hash, 256) < 0) exit(2);
    int64_t total = 0;
    for (size_t i = 0; i < frames.size(); i += group) {
      Bytes pcm;
      int bytes = decoder.decode(join(frames, i, std::min(group, frames.size() - i)), 4096, pcm);
      if (bytes <= 0) { EXPECT(false, "decode failed at frame %zu, group %zu", i, group); break; }
      EXPECT(decoder.context->ch_layout.nb_channels == 6 && decoder.context->sample_rate == 48000,
             "expected reference recording's 6 channels at 48 kHz");
      const float *samples = reinterpret_cast<const float *>(pcm.data());
      for (size_t n = 0; n < pcm.size() / sizeof(float); n++)
        if (!std::isfinite(samples[n])) { EXPECT(false, "non-finite PCM at frame %zu", i); break; }
      av_sha_update(hash, pcm.data(), pcm.size());
      total += bytes;
    }
    std::vector<uint8_t> digest(32);
    av_sha_final(hash, digest.data());
    av_free(hash);
    if (group == 1) { reference = digest; referenceBytes = total; }
    else EXPECT(digest == reference && total == referenceBytes, "complete recording differs for group %zu", group);
    EXPECT(total == (int64_t)frames.size() * 1536 * 6 * 4, "incorrect complete-recording frame count");
    printf("Complete recording: %zu packets/group, %zu syncframes, %lld PCM bytes, ", group, frames.size(), (long long)total);
    for (uint8_t byte : digest) printf("%02x", byte);
    printf("\n");
  }
}
}  // namespace

int main(int argc, char **argv) {
  if (argc != 3 && argc != 4) {
    fprintf(stderr, "usage: %s owned-51.eac3 owned-stereo.eac3 [clear-51-recording.eac3]\n", argv[0]);
    return 2;
  }
  auto surround = syncframes(argv[1]);
  auto stereo = syncframes(argv[2]);
  if (surround.size() < 10 || stereo.size() < 3) {
    fprintf(stderr, "fixtures too short\n");
    return 2;
  }
  oneSyncframePerPacketKeepsSixDistinctFiniteChannels(surround);
  severalSyncframesInOnePacketAreContiguous(surround, true);
  severalSyncframesInOnePacketAreContiguous(surround, false);
  growingTheOutputBufferKeepsEarlierFrames(surround);
  exactCapacityNeverWritesPastTheBuffer(surround);
  aLayoutChangeRebuildsTheConverter(surround, stereo);
  invalidDataIsReportedWithoutOutput();
  if (argc == 4) completeRecordingMatchesAcrossPacketGroups(argv[3]);
  if (failures) {
    fprintf(stderr, "%d native decoder check(s) failed\n", failures);
    return 1;
  }
  printf("All native decoder checks passed\n");
  return 0;
}
