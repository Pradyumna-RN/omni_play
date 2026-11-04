// Host contract probe for the pinned public OpenJOC stream API.
// Pass the authorized UltraViolet_Untie reference used by the JVM tests.
// Its first 300 syncframes each encode 1,536 samples; no audio is distributed here.
#include "openjoc.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <vector>

static void require(bool value, const char* message) {
  if (!value) throw std::runtime_error(message);
}

struct Result { std::vector<float> pcm; unsigned refusals = 0; };

static Result decode(const std::vector<unsigned char>& encoded, bool forcePending) {
  openjoc_decoder_config config{};
  require(openjoc_decoder_config_init_v1_7(&config) == OPENJOC_STATUS_OK, "config");
  config.render_mode = OPENJOC_RENDER_SPEAKER;
  config.speaker_layout = "7.1.4";
  config.drc = OPENJOC_DRC_DISABLED;
  openjoc_stream_decoder* raw = nullptr;
  require(openjoc_stream_decoder_create(&config, &raw) == OPENJOC_STATUS_OK, "create");
  std::unique_ptr<openjoc_stream_decoder, decltype(&openjoc_stream_decoder_destroy)>
      decoder(raw, openjoc_stream_decoder_destroy);
  Result result;
  auto receive = [&]() {
    openjoc_pcm_frame frame{};
    require(openjoc_pcm_frame_init(&frame) == OPENJOC_STATUS_OK, "frame");
    while (true) {
      auto status = openjoc_stream_decoder_receive_frame(raw, &frame);
      if (status != OPENJOC_STATUS_FRAME_AVAILABLE) return status;
      require(frame.channel_count == 12 && frame.sample_rate == 48000, "format");
      require(frame.data_len == frame.sample_count * 12 * sizeof(float), "size");
      result.pcm.insert(result.pcm.end(), frame.data, frame.data + frame.sample_count * 12);
    }
  };
  size_t at = 0;
  unsigned calls = 0;
  while (at < encoded.size()) {
    require(++calls < 10000, "no progress");
    const auto count = std::min<size_t>(6 * 3072, encoded.size() - at);
    auto status = openjoc_stream_decoder_send_chunk(raw, encoded.data() + at, count, OPENJOC_NO_PTS, 0);
    if (status == OPENJOC_STATUS_OUTPUT_PENDING) {
      ++result.refusals;
      require(receive() == OPENJOC_STATUS_NEED_MORE_INPUT, "receive blocked output");
      // Retry exactly the same bytes. If the refused call consumed anything,
      // comparison with the normally drained decoder below will fail.
      continue;
    }
    require(status == OPENJOC_STATUS_OK || status == OPENJOC_STATUS_FRAME_AVAILABLE ||
            status == OPENJOC_STATUS_NEED_MORE_INPUT, "send");
    at += count;
    if (!forcePending || at == encoded.size())
      require(receive() == OPENJOC_STATUS_NEED_MORE_INPUT, "receive");
  }
  auto status = openjoc_stream_decoder_drain(raw);
  require(status == OPENJOC_STATUS_OK || status == OPENJOC_STATUS_FRAME_AVAILABLE ||
          status == OPENJOC_STATUS_NEED_MORE_INPUT || status == OPENJOC_STATUS_END_OF_STREAM ||
          status == OPENJOC_STATUS_OUTPUT_PENDING, "drain");
  require(receive() == OPENJOC_STATUS_END_OF_STREAM, "end of stream");
  return result;
}

int main(int argc, char** argv) {
  try {
    require(argc == 2, "usage: backpressure_probe recording.eac3");
    std::ifstream file(argv[1], std::ios::binary);
    require(file.good(), "recording unavailable");
    std::vector<unsigned char> encoded((std::istreambuf_iterator<char>(file)), {});
    size_t end = 0;
    for (int i = 0; i < 300; ++i) {
      require(end + 6 < encoded.size() && encoded[end] == 0x0b && encoded[end + 1] == 0x77, "syncframe");
      end += ((((encoded[end + 2] & 7) << 8) | encoded[end + 3]) + 1) * 2;
    }
    require(end <= encoded.size(), "frame bounds");
    encoded.resize(end);
    const auto baseline = decode(encoded, false);
    const auto retry = decode(encoded, true);
    require(baseline.refusals == 0 && retry.refusals > 0, "pending path not exercised");
    require(baseline.pcm.size() == (300 * 1536 + 32) * 12, "duration");
    require(baseline.pcm.size() == retry.pcm.size() &&
            std::memcmp(baseline.pcm.data(), retry.pcm.data(), baseline.pcm.size() * sizeof(float)) == 0,
            "retry duplicated or lost input");
    std::printf("{\"outputPendingRefusals\":%u,\"samplesPerChannel\":%zu,\"retryPcmMatches\":true}\n",
                retry.refusals, retry.pcm.size() / 12);
    return 0;
  } catch (const std::exception& error) { std::fprintf(stderr, "%s\n", error.what()); return 1; }
}
