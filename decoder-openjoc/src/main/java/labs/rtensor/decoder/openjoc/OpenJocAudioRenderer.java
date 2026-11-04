package labs.rtensor.decoder.openjoc;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import java.util.function.BooleanSupplier;

@UnstableApi
public class OpenJocAudioRenderer extends DecoderAudioRenderer<OpenJocAudioDecoder> {
  private final BooleanSupplier enabled;
  public OpenJocAudioRenderer(Handler handler, AudioRendererEventListener listener, AudioSink sink, BooleanSupplier enabled) {
    super(handler,listener,sink);this.enabled=enabled;
  }
  @Override public String getName() { return "MusicOpenJocSceneRenderer"; }
  @Override protected int supportsFormatInternal(Format format) {
    if (!MimeTypes.AUDIO_E_AC3_JOC.equals(format.sampleMimeType) || !enabled.getAsBoolean() || !OpenJocLibrary.isAvailable()) return C.FORMAT_UNSUPPORTED_TYPE;
    if (format.cryptoType!=C.CRYPTO_TYPE_NONE || format.drmInitData!=null) return C.FORMAT_UNSUPPORTED_DRM;
    if (format.sampleRate!=Format.NO_VALUE && format.sampleRate!=48000) return C.FORMAT_UNSUPPORTED_SUBTYPE;
    return sinkSupportsFormat(new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(48000).setChannelCount(12).setPcmEncoding(C.ENCODING_PCM_FLOAT).build())
        ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_SUBTYPE;
  }
  @Override public int supportsMixedMimeTypeAdaptation() { return ADAPTIVE_NOT_SEAMLESS; }
  @Override protected OpenJocAudioDecoder createDecoder(Format format, @Nullable CryptoConfig crypto) throws OpenJocException {
    return new OpenJocAudioDecoder(format.maxInputSize>0?format.maxInputSize:65536);
  }
  @Override protected Format getOutputFormat(OpenJocAudioDecoder decoder) {
    return new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(decoder.getSampleRate())
        .setChannelCount(decoder.getChannelCount()).setPcmEncoding(C.ENCODING_PCM_FLOAT).build();
  }
}
