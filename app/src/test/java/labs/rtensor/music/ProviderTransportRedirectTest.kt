package labs.rtensor.music

import labs.rtensor.music.data.ProviderTransport
import labs.rtensor.music.data.WebSession
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic application interceptors exercise redirect/cookie policy without network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderTransportRedirectTest {
    private val session = WebSession("SAPISID=fixture-cookie", "fixture-visitor", authUser = 1)

    private fun options(method: String = "GET", media: Boolean = false) = JSONObject().put("method", method)
        .put("headers", JSONObject().put("authorization", "SAPISIDHASH fixture-auth")
            .put("x-goog-authuser", "1").put("x-goog-pageid", "123")
            .put("x-goog-visitor-id", "fixture-visitor").put("x-goog-session-index", "1")
            .put("x-youtube-identity-token", "fixture-identity")
            .put("origin", "https://music.youtube.com").put("referer", "https://music.youtube.com/"))
        .apply { if (media) getJSONObject("headers").put("range", "bytes=0-1") }
        .put("body", if (method == "POST") "{}" else "").toString()

    private fun response(request: Request, code: Int = 200, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body("fixture-body".toResponseBody()).apply { if (location != null) header("Location", location) }.build()

    @Test fun relativeFirstPartyRedirectsPreserveMethodAndSameOriginCredentials() {
        for (status in listOf(301, 302, 303, 307, 308)) for (method in listOf("GET", "HEAD")) {
            val seen = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); seen += request
                if (seen.size == 1) response(request, status, "/landing?authuser=1") else response(request)
            }.build()
            val result = ProviderTransport(client = client).execute("fixture", "https://music.youtube.com/", options(method), "fixture-agent", session)
            assertEquals("$status $method: $result", 200, result.optInt("status"))
            assertEquals(2, seen.size)
            assertEquals(method, seen.last().method)
            assertEquals(session.cookie, seen.last().header("Cookie"))
            assertEquals("SAPISIDHASH fixture-auth", seen.last().header("Authorization"))
            assertEquals("1", seen.last().header("X-Goog-Authuser"))
            assertEquals("https://music.youtube.com/landing?authuser=1", result.getString("url"))
            assertEquals(1, result.getInt("redirects"))
        }
    }

    @Test fun anonymousPlayerRequestsCarryNoAccountAndUseTheClientAgent() {
        val o = JSONObject(options("POST"))
        o.getJSONObject("headers").put("X-Music-Anonymous", "1").put("X-Music-User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) Safari/605.1.15")
        val request = ProviderTransport().request("https://www.youtube.com/youtubei/v1/player", o.toString(), "fixture-agent", session)
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        assertNull(request.header("X-Goog-Authuser")); assertNull(request.header("X-Goog-PageId"))
        assertNull(request.header("X-Music-Anonymous")); assertNull(request.header("X-Music-User-Agent"))
        assertNull(request.header("X-Goog-Session-Index")); assertNull(request.header("X-Youtube-Identity-Token"))
        assertEquals("fixture-visitor", request.header("X-Goog-Visitor-Id"))
        assertEquals("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) Safari/605.1.15", request.header("User-Agent"))
        val signed = ProviderTransport().request("https://www.youtube.com/youtubei/v1/player", JSONObject(options("POST")).apply {
            getJSONObject("headers").put("X-Music-User-Agent", "spoofed") }.toString(), "fixture-agent", session)
        assertEquals(session.cookie, signed.header("Cookie")); assertEquals("fixture-agent", signed.header("User-Agent"))
    }

    @Test fun crossingOriginDropsCredentialsAndDoesNotReintroduceThemOnReturning() {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            when (seen.size) {
                1 -> response(request, 302, "https://www.youtube.com/landing")
                2 -> response(request, 307, "https://music.youtube.com/done")
                else -> response(request)
            }
        }.build()
        val result = ProviderTransport(client = client).execute("fixture", "https://music.youtube.com/", options(), "fixture-agent", session)
        assertEquals(200, result.optInt("status"))
        assertEquals(3, seen.size)
        assertEquals(session.cookie, seen.first().header("Cookie"))
        seen.drop(1).forEach { request ->
            listOf("Cookie", "Authorization", "X-Goog-Authuser", "X-Goog-PageId", "X-Goog-Visitor-Id",
                "X-Goog-Session-Index", "X-Youtube-Identity-Token", "Origin", "Referer").forEach { header -> assertNull(header, request.header(header)) }
            assertEquals("fixture-agent", request.header("User-Agent"))
        }
    }

    @Test fun unsafeOrInteractiveRedirectDestinationsAreNeverRequested() {
        val targets = listOf("https://accounts.google.com/ServiceLogin", "https://consent.youtube.com/", "https://consent.google.com/",
            "https://music.youtube.com.attacker.test/", "//attacker.test/", "http://music.youtube.com/", "https://music.youtube.com:444/",
            "https://someone@music.youtube.com/", "file:///tmp/test", "javascript:alert(1)")
        for (target in targets) {
            val seen = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain -> seen += chain.request(); response(chain.request(), 302, target) }.build()
            val result = ProviderTransport(client = client).execute("fixture", "https://music.youtube.com/", options(), "fixture-agent", session)
            assertEquals(target, 1, seen.size)
            assertEquals(target, 302, result.optInt("status"))
            assertEquals("https://music.youtube.com/", result.getString("url"))
        }
    }

    @Test fun redirectLoopsStopAfterFourHops() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain -> requests++; response(chain.request(), 302, "/again") }.build()
        val result = ProviderTransport(client = client).execute("fixture", "https://music.youtube.com/", options(), "fixture-agent", session)
        assertEquals(5, requests)
        assertEquals(302, result.optInt("status"))
        assertEquals(4, result.getInt("redirects"))
    }

    @Test fun postMediaAndProofRequestsNeverFollowRedirects() {
        data class Case(val url: String, val method: String = "GET", val media: Boolean = false, val proof: Boolean = false)
        for (case in listOf(Case("https://music.youtube.com/youtubei/v1/browse", method = "POST"),
            Case("https://r1-fixture.googlevideo.com/videoplayback", media = true),
            Case("https://www.google.com/js/bg/fixture.js", proof = true))) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain -> requests++; response(chain.request(), 302, "https://music.youtube.com/next") }.build()
            val result = ProviderTransport(proofOnly = case.proof, client = client).execute("fixture", case.url, options(case.method, case.media), "fixture-agent", session)
            assertEquals(case.url, 1, requests)
            assertEquals(case.url, 302, result.optInt("status"))
            assertEquals(0, result.getInt("redirects"))
        }
    }

    @Test fun cancellationKeepsTheSameRequestIdAcrossTheRedirectChain() {
        var requests = 0
        lateinit var transport: ProviderTransport
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (requests == 2) transport.cancel("fixture")
            response(chain.request(), 302, "/next")
        }.build()
        transport = ProviderTransport(client = client)
        val result = transport.execute("fixture", "https://music.youtube.com/", options(), "fixture-agent", session)
        assertEquals(2, requests)
        assertEquals("NETWORK", result.optString("error"))
    }
}
