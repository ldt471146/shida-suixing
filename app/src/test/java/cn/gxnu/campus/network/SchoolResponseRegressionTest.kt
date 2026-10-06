package cn.gxnu.campus.network

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.PortalException
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLDecoder
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Full public school configuration; every response and credential below is in memory. */
class SchoolResponseRegressionTest {
    private val campus = NetworkSnapshot("school-response-fixture", "GXNU-YC", true)
    private val credentials = Credentials("fixture-school-account", "fixture-school-password")
    private val script = "var page_data_encrypt='0'; var jsVersion='4.2.2';"
    private val html = "<script>v4ip='10.20.30.40'; ss4='0223456789ab';</script><script src='a41.js'></script>"
    private val configuration by lazy {
        requireNotNull(javaClass.getResourceAsStream("/portal/gxnu-load-config.jsonp")) {
            "The sanitized complete school configuration fixture is required"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test fun completeSchoolConfigurationCanReachCredentialSubmission() = runTest {
        val requests = mutableListOf<Pair<NetworkSnapshot, URL>>()
        val client = schoolClient(requests)

        assertTrue(client.authenticate(campus, credentials, Provider.CAMPUS))

        val login = requests.single { it.second.path == "/eportal/portal/login" }.second
        assertEquals("https", login.protocol)
        assertEquals("yc.gxnu.edu.cn", login.host)
        assertEquals(802, login.port)
        val parameters = query(login)
        assertEquals(",1,fixture-school-account", parameters["user_account"])
        assertEquals("fixture-school-password", parameters["user_password"])
        assertEquals("10.20.30.40", parameters["wlan_user_ip"])
        assertEquals("0223456789ab", parameters["wlan_user_mac"])
        assertTrue(requests.all { it.first == campus })
        assertTrue(requests.filter { it.second != login }.all {
            !query(it.second).containsKey("user_account") && !query(it.second).containsKey("user_password")
        })
    }

    @Test fun loadConfigCarriesSchoolScriptVersionLanguageAndCacheNonce() = runTest {
        val requests = mutableListOf<Pair<NetworkSnapshot, URL>>()

        assertTrue(schoolClient(requests).authenticate(campus, credentials, Provider.CAMPUS))

        val loadConfig = requests.single { it.second.path == "/eportal/portal/page/loadConfig" }.second
        val parameters = query(loadConfig)
        assertEquals("The configuration endpoint needs the school's script version", "4.2.2", parameters["jsVersion"])
        assertEquals("The configuration endpoint needs the school's UI language", "zh-cn", parameters["lang"])
        assertTrue("The configuration endpoint needs a numeric cache nonce", parameters["v"]?.matches(Regex("[0-9]+")) == true)
    }

    @Test fun malformedRequiredTerminalStatusIdentifiesItsReadPhaseWithoutEchoingPayload() = runTest {
        val requests = mutableListOf<Pair<NetworkSnapshot, URL>>()
        val response = """dr1002({"result":0,"ss5":,"echo":"fixture-school-account fixture-school-password fixture-raw-status"});"""
        val client = schoolClient(
            requests,
            page = "<script src='a41.js'></script>",
            terminalStatus = response
        )

        val failure = authenticationFailure(client)

        assertSafePhaseFailure(failure, "终端状态", "fixture-raw-status", response)
        assertTrue(requests.any { it.second.path == "/drcom/chkstatus" })
        assertFalse(requests.any { it.second.path == "/eportal/portal/login" })
    }

    @Test fun malformedConfigurationIdentifiesItsReadPhaseWithoutEchoingPayload() = runTest {
        val requests = mutableListOf<Pair<NetworkSnapshot, URL>>()
        val response = """dr938({"code":1,"data":{"login_method":,"echo":"fixture-school-account fixture-school-password fixture-raw-config"}});"""
        val client = schoolClient(requests, config = response)

        val failure = authenticationFailure(client)

        assertSafePhaseFailure(failure, "认证配置", "fixture-raw-config", response)
        assertTrue(requests.any { it.second.path == "/eportal/portal/page/loadConfig" })
        assertFalse(requests.any { it.second.path == "/eportal/portal/login" })
    }

    private fun schoolClient(
        requests: MutableList<Pair<NetworkSnapshot, URL>>,
        page: String = html,
        config: String = configuration,
        terminalStatus: String = """dr1002({"result":0,"ss5":"10.20.30.40","ss4":"0223456789ab"});"""
    ): PortalClient = PortalClient(TargetConnectionFactory { network, url ->
        requests += network to url
        val body = when (url.path) {
            "/" -> page
            "/a41.js" -> script
            "/drcom/chkstatus" -> terminalStatus
            "/eportal/portal/page/loadConfig" -> config
            "/eportal/portal/login" -> """dr1003({"result":1,"msg":"fixture success"});"""
            else -> throw AssertionError("Unexpected fixture endpoint: ${url.path}")
        }
        MemoryReply(url, body.toByteArray(Charsets.UTF_8))
    })

    private suspend fun authenticationFailure(client: PortalClient): PortalException {
        try {
            client.authenticate(campus, credentials, Provider.CAMPUS)
        } catch (failure: PortalException) {
            return failure
        }
        throw AssertionError("Malformed school data must stop before credential submission")
    }

    private fun assertSafePhaseFailure(failure: PortalException, phase: String, marker: String, payload: String) {
        assertEquals(PortalFailure.INVALID_RESPONSE, failure.reason)
        assertTrue("The error must identify which school response could not be read", failure.message.orEmpty().contains(phase))
        val detail = failure.toString()
        for (sensitive in listOf(credentials.account, credentials.password, marker, payload, "user_password", "https://")) {
            assertFalse("School response details must not be echoed", detail.contains(sensitive))
        }
        assertNull("The raw parser exception must not escape", failure.cause)
    }

    private fun query(url: URL): Map<String, String> = url.query.orEmpty().split('&')
        .filter { it.isNotEmpty() }.associate { field ->
            val parts = field.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

    private class MemoryReply(url: URL, body: ByteArray) : HttpsURLConnection(url) {
        private val stream = ByteArrayInputStream(body)
        override fun getResponseCode() = 200
        override fun getInputStream() = stream
        override fun getHeaderField(name: String?) = null
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal = Principal { "fixture" }
        override fun getLocalPrincipal(): Principal? = null
    }
}
