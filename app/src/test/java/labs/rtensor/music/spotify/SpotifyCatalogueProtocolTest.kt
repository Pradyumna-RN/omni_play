package labs.rtensor.music.spotify

import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class SpotifyCatalogueProtocolTest {
    @Test fun receiverRoundTripWorksWithoutAnApplicationClassLoaderAndReturnsToTheOriginalCallback() {
        var code: Int? = null; var profile: String? = null
        val local = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { code = resultCode; profile = resultData?.getString("result") }
        }
        val parcel = Parcel.obtain()
        try {
            SpotifyCatalogueProtocol.writeReceiver(parcel, local)
            parcel.setDataPosition(0)
            val remote = SpotifyCatalogueProtocol.readReceiver(parcel)
            assertEquals(ResultReceiver::class.java, remote.javaClass)
            // Robolectric's ResultReceiver shadow bypasses Binder; exercise the real framework send.
            org.robolectric.shadow.api.Shadow.directlyOn<Unit, ResultReceiver>(remote, ResultReceiver::class.java, "send",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Integer.TYPE, 0),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Bundle::class.java, Bundle().apply { putString("result", "{\"name\":\"Listener\"}") }))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, code); assertEquals("{\"name\":\"Listener\"}", profile)
        } finally { parcel.recycle() }
    }

    @Test fun oldParcelableEncodingNeedsTheApplicationReceiverClassThatTheFrameworkLoaderCannotLoad() {
        val local = object : ResultReceiver(null) {}
        val frameworkOnly = object : ClassLoader(ResultReceiver::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == local.javaClass.name) throw ClassNotFoundException("Application classes are unavailable to the framework loader")
                return super.loadClass(name, resolve)
            }
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(local, 0); parcel.setDataPosition(0)
            @Suppress("DEPRECATION") val failure = runCatching { parcel.readParcelable<ResultReceiver>(frameworkOnly) }.exceptionOrNull()
            assertNotNull("The previous encoding could not cross the framework-only class-loader boundary", failure)
        } finally { parcel.recycle() }
    }
}
