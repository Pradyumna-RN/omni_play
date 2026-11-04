package labs.rtensor.decoder.openjoc;

/** Optional, pinned speaker-only build; the app supplies its own DMR and HRTF stages. */
public final class OpenJocLibrary {
  private static final Throwable ERROR;
  static {
    Throwable failure = null;
    try {
      System.loadLibrary("openjoc_capi");
      System.loadLibrary("openjocJNI");
      if (abiVersion() != 0x10007) throw new UnsatisfiedLinkError("Expected OpenJOC ABI 1.7");
    } catch (Throwable e) { failure = e; }
    ERROR = failure;
  }
  private OpenJocLibrary() {}
  public static boolean isAvailable() { return ERROR == null; }
  public static String unavailableReason() { return ERROR == null ? null : ERROR.getClass().getSimpleName(); }
  private static native int abiVersion();
}
