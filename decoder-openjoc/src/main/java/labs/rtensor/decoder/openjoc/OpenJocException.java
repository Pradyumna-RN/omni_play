package labs.rtensor.decoder.openjoc;

import androidx.media3.decoder.DecoderException;

public final class OpenJocException extends DecoderException {
  public OpenJocException(String message) { super(message); }
  public OpenJocException(String message, Throwable cause) { super(message, cause); }
}
