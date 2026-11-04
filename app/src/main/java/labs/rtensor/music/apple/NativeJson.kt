package labs.rtensor.music.apple

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Platform JSON for the optional native adapter. No parser library is bundled. */
internal object NativeJson {
    private fun key(value: ByteArray) = value.toString(Charsets.UTF_8)
    @JvmStatic fun objectValue(): Any = JSONObject()
    @JvmStatic fun arrayValue(): Any = JSONArray()
    @JvmStatic fun string(value: ByteArray): Any = key(value)
    @JvmStatic fun boolean(value: Boolean): Any = value
    @JvmStatic fun number(value: Double): Any? = value.takeIf(Double::isFinite)
    @JvmStatic fun integer(value: Long): Any = value
    @JvmStatic fun parse(value: ByteArray): Any? {
        require(value.size <= 2 * 1024 * 1024)
        val tokenizer = JSONTokener(key(value))
        val result = tokenizer.nextValue()
        require(tokenizer.nextClean() == '\u0000')
        return result.takeUnless { it === JSONObject.NULL }
    }
    @JvmStatic fun dump(value: Any?): ByteArray = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is JSONObject, is JSONArray, is Boolean, is Number -> value.toString()
        else -> error("Unsupported JSON value")
    }.toByteArray(Charsets.UTF_8).also { require(it.size <= 2 * 1024 * 1024) }
    @JvmStatic fun kind(value: Any?): Int = when (value) {
        null, JSONObject.NULL -> 0; is JSONObject -> 1; is JSONArray -> 2
        is String -> 3; is Boolean -> 4; is Byte, is Short, is Int, is Long -> 5
        is Number -> 6; else -> error("Unsupported JSON value")
    }
    @JvmStatic fun empty(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> true; is JSONObject -> value.length() == 0
        is JSONArray -> value.length() == 0; is String -> value.isEmpty(); else -> false
    }
    @JvmStatic fun contains(parent: Any?, name: ByteArray) = (parent as? JSONObject)?.has(key(name)) == true
    @JvmStatic fun get(parent: Any?, name: ByteArray): Any? = (parent as? JSONObject)?.opt(key(name))?.takeUnless { it === JSONObject.NULL }
    @JvmStatic fun put(parent: Any?, name: ByteArray, value: Any?) { (parent as JSONObject).put(key(name), value ?: JSONObject.NULL) }
    @JvmStatic fun append(parent: Any?, value: Any?) { (parent as JSONArray).put(value ?: JSONObject.NULL) }
    @JvmStatic fun stringBytes(value: Any?): ByteArray = (value as String).toByteArray(Charsets.UTF_8)
    @JvmStatic fun longValue(value: Any?) = (value as Number).toLong()
}
