package labs.rtensor.dsp

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
object PcmFloatConverter {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> throw IllegalArgumentException("Unsupported decoded PCM encoding: $encoding")
    }
    fun convert(input: ByteBuffer, output: ByteBuffer, samples: Int, encoding: Int) {
        require(samples >= 0)
        require(input.order() == ByteOrder.nativeOrder() && output.order() == ByteOrder.nativeOrder())
        require(input.remaining() >= Math.multiplyExact(samples, bytesPerSample(encoding)))
        require(output.remaining() >= Math.multiplyExact(samples, 4))
        repeat(samples) {
            val sample = when (encoding) {
                C.ENCODING_PCM_FLOAT -> input.float
                C.ENCODING_PCM_16BIT -> input.short / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val low = input.get().toInt() and 255
                    val middle = input.get().toInt() and 255
                    val high = input.get().toInt()
                    (low or (middle shl 8) or (high shl 16)) / 8388608f
                }
                C.ENCODING_PCM_32BIT -> input.int / 2147483648f
                else -> error("Unsupported PCM encoding")
            }
            output.putFloat(sample)
        }
    }
}
