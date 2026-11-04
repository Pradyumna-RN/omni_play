package labs.rtensor.music.apple

/** Loaded only in the private Apple worker process (or explicit native tests). */
internal object NativeAppleBridge {
    init { System.loadLibrary("musicappleJNI") }
    @JvmStatic external fun initialize(directory: String, session: String, device: String, order: Array<String>): ByteArray
    @JvmStatic external fun account(operation: Int, user: ByteArray, secret: ByteArray): ByteArray
    @JvmStatic external fun credentials(): ByteArray
    @JvmStatic external fun playback(songId: String): ByteArray
    @JvmStatic external fun decrypt(songId: String, keyUri: String, input: ByteArray): ByteArray
    @JvmStatic external fun jsonRoundTrip(input: ByteArray, worker: Boolean): ByteArray
}
