package cn.gxnu.campus.network

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.PortalException
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class PortalClientTest {
    private val campus = NetworkSnapshot("fixture-wifi-42", "GXNU-YC", true)
    private val html = "<script>v4ip='10.20.30.40';</script><script src='a41.js?v=fixture'></script>"
    private val script = "var page_data_encrypt='0'; var jsVersion='4.2.2';"
    private val config = """dr938({"code":1,"msg":"fixture","data":{"login_method":"1","enable_r3":"0","en_md5":"0","account_prefix":"1","io_mode":"0","en_perceive":"0","enable_alias":"0"}});"""

    private open class Reply(url: URL, private val code: Int, body: ByteArray = ByteArray(0), private val redirect: String? = null) : HttpsURLConnection(url) {
        private val stream = ByteArrayInputStream(body)
        override fun getResponseCode() = code
        override fun getInputStream() = stream
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) redirect else null
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal = Principal { "fixture" }
        override fun getLocalPrincipal(): Principal? = null
    }

    private fun clientWithLoginResponse(loginBody: String) = PortalClient(TargetConnectionFactory { _, url ->
        val body = when (url.path) {
            "/" -> html
            "/a41.js" -> script
            "/eportal/portal/page/loadConfig" -> config
            "/drcom/chkstatus" -> """{"result":0,"ss4":"0223456789ab"}"""
            "/eportal/portal/login" -> loginBody
            else -> throw AssertionError("Unexpected school endpoint")
        }
        Reply(url, 200, body.toByteArray())
    })

    @Test fun schoolChallengeTimeoutIsReportedWithoutCallingItAnAccountFailure() = runTest {
        val client = clientWithLoginResponse("""dr1003({"result":0,"ret_code":6,"msg":"REQ_CHALLENGE timeout"});""")
        val failure = try {
            client.authenticate(campus, Credentials("fixture-student", "fixture-password"), Provider.CAMPUS)
            throw AssertionError("A school timeout must be a visible, classified failure")
        } catch (error: PortalException) { error }
        assertEquals(PortalFailure.TIMEOUT, failure.reason)
        assertTrue(failure.message.orEmpty().contains("超时"))
    }

    @Test fun alreadyOnlineSchoolReplyStillAllowsTheCoordinatorToVerifyThisWifi() = runTest {
        val client = clientWithLoginResponse("""{"result":0,"ret_code":2,"msg":"终端IP已经在线"}""")
        assertTrue(client.authenticate(campus, Credentials("fixture-student", "fixture-password"), Provider.CAMPUS))
    }

    @Test fun accountRejectionKeepsUsefulSchoolReasonWithoutEchoingCredentials() = runTest {
        val client = clientWithLoginResponse("""{"result":0,"ret_code":1,"msg":"fixture-student: fixture-password 账号密码错误"}""")
        val failure = try {
            client.authenticate(campus, Credentials("fixture-student", "fixture-password"), Provider.CAMPUS)
            throw AssertionError("The school rejection must reach the user")
        } catch (error: PortalException) { error }
        assertEquals(PortalFailure.ACCOUNT, failure.reason)
        assertTrue(failure.message.orEmpty().contains("密码"))
        assertFalse(failure.toString().contains("fixture-student"))
        assertFalse(failure.toString().contains("fixture-password"))
    }

    @Test fun schoolRedirectSuppliesTheTerminalQueryBeforeAnyCredentialRequest() = runTest {
        val requests = mutableListOf<Pair<NetworkSnapshot, URL>>()
        val bootstrap = "<script src='a41.js?v=fixture'></script>"
        val client = PortalClient(TargetConnectionFactory { network, url ->
            requests += network to url
            if (url.path == "/" && url.query == null) {
                Reply(url, 302, redirect = "/?wlanuserip=10.42.0.8&wlanusermac=0223456789ab&nas-ip=10.42.0.1&nas-name=fixture-ac&vlanid=7&essid=GXNU-YC")
            } else {
                val body = when (url.path) {
                    "/" -> bootstrap
                    "/a41.js" -> script
                    "/eportal/portal/page/loadConfig" -> config
                    "/eportal/portal/login" -> """{"result":1}"""
                    else -> throw AssertionError("Unexpected school endpoint")
                }
                Reply(url, 200, body.toByteArray())
            }
        })
        assertTrue(client.authenticate(campus, Credentials("fixture-student", "fixture-password"), Provider.CAMPUS))
        assertTrue(requests.all { it.first == campus })
        val loginQuery = requests.single { it.second.path == "/eportal/portal/login" }.second.query
        assertTrue(loginQuery.contains("wlan_user_ip=10.42.0.8"))
        assertTrue(loginQuery.contains("wlan_user_mac=0223456789ab"))
        assertTrue(loginQuery.contains("wlan_ac_name=fixture-ac"))
        val configurationQuery = requests.single { it.second.path.endsWith("loadConfig") }.second.query
        assertTrue(configurationQuery.contains("wlan_vlan_id=7"))
        assertTrue(configurationQuery.contains("wlan_user_ssid=GXNU-YC"))
        assertTrue(requests.filter { it.second.path != "/eportal/portal/login" }.none {
            it.second.query.orEmpty().contains("user_password")
        })
    }

    @Test fun bootstrapWithoutAddressCanRecoverItFromReadOnlySchoolStatus() = runTest {
        val requests = mutableListOf<URL>()
        val client = PortalClient(TargetConnectionFactory { _, url ->
            requests += url
            val body = when (url.path) {
                "/" -> "<script src='a41.js'></script>"
                "/a41.js" -> script
                "/drcom/chkstatus" -> """dr1002({"result":0,"ss3":"0a09100a","ss4":"02:23:45:67:89:ab"});"""
                "/eportal/portal/page/loadConfig" -> config
                "/eportal/portal/login" -> """{"result":1}"""
                else -> throw AssertionError("Unexpected school endpoint")
            }
            Reply(url, 200, body.toByteArray())
        })
        assertTrue(client.authenticate(campus, Credentials("fixture-student", "fixture-password"), Provider.CAMPUS))
        val loginQuery = requests.single { it.path == "/eportal/portal/login" }.query
        assertTrue(loginQuery.contains("wlan_user_ip=10.9.16.10"))
        assertTrue(loginQuery.contains("wlan_user_mac=0223456789ab"))
        assertTrue(requests.indexOfFirst { it.path == "/drcom/chkstatus" } < requests.indexOfFirst { it.path.endsWith("loadConfig") })
    }

    @Test fun discoveryRedirectCannotLeaveTheSchoolOrCarryCredentials() = runTest {
        val requests = mutableListOf<URL>()
        val client = PortalClient(TargetConnectionFactory { _, url ->
            requests += url
            Reply(url, 302, redirect = "https://outside.invalid/captive")
        })
        val failure = try {
            client.authenticate(campus, Credentials("fixture-student", "fixture-password"), Provider.CAMPUS)
            throw AssertionError("External redirects must stop before credentials")
        } catch (error: PortalException) { error }
        assertEquals(PortalFailure.UNSUPPORTED, failure.reason)
        assertEquals(1, requests.size)
        assertFalse(requests.single().toString().contains("fixture-password"))
    }

    @Test fun cancellationNeverRunsBlockingDisconnectOnTheCancellingThread() = runBlocking {
        val cancellingThread = Thread.currentThread()
        val readStarted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val disconnectOnCaller = AtomicBoolean(false)
        val client = PortalClient(TargetConnectionFactory { _, url ->
            object : Reply(url, 204) {
                override fun getResponseCode(): Int {
                    readStarted.countDown()
                    check(disconnected.await(5, TimeUnit.SECONDS))
                    return 204
                }
                override fun disconnect() {
                    if (Thread.currentThread() == cancellingThread) disconnectOnCaller.set(true)
                    disconnected.countDown()
                }
            }
        })
        val job = launch { client.verifyInternet(campus) }
        assertTrue(withContext(Dispatchers.IO) { readStarted.await(2, TimeUnit.SECONDS) })
        job.cancel()
        job.join()
        assertFalse("Cancellation must leave the caller/UI thread free", disconnectOnCaller.get())
        assertTrue(job.isCancelled)
    }

    @Test fun authenticateDiscoversConfigurationWithoutCredentialsBeforeSubmittingOnSameNetwork() = runTest {
        val requests = mutableListOf<Pair<NetworkSnapshot, URL>>()
        val factory = TargetConnectionFactory { network, url ->
            requests += network to url
            val body = when (url.path) {
                "/" -> html.toByteArray(Charsets.UTF_8)
                "/a41.js" -> script.toByteArray(Charsets.UTF_8)
                "/eportal/portal/page/loadConfig" -> config.toByteArray(Charsets.UTF_8)
                "/drcom/chkstatus" -> "dr1002({\"result\":0,\"ss4\":\"02:23:45:67:89:ab\",\"ss5\":\"10.20.30.40\"});".toByteArray()
                "/eportal/portal/login" -> "dr1003({\"result\":1,\"msg\":\"fixture\"});".toByteArray(Charsets.UTF_8)
                else -> error("unexpected endpoint")
            }
            Reply(url, 200, body)
        }
        assertTrue(PortalClient(factory).authenticate(campus, Credentials("fixture-student", " p+密码&= "), Provider.MOBILE))
        assertEquals(5, requests.size)
        assertTrue(requests.all { it.first == campus })
        assertTrue(requests.take(4).none { it.second.query.orEmpty().contains("user_password") })
        val discoveryQuery = requests[2].second.query
        assertTrue(discoveryQuery.contains("wlan_user_ip=MTAuMjAuMzAuNDA%3D"))
        assertEquals("/drcom/chkstatus", requests[3].second.path)
        val loginQuery = requests[4].second.query
        assertTrue(loginQuery.contains("user_account=%2C1%2Cfixture-student%40cmc"))
        assertTrue(loginQuery.contains("user_password=+p%2B%E5%AF%86%E7%A0%81%26%3D+"))
        assertTrue(loginQuery.contains("wlan_user_mac=0223456789ab"))
    }

    @Test fun unknownEncryptionConfigurationNeverReachesCredentialEndpoint() = runTest {
        val requests = mutableListOf<String>()
        val factory = TargetConnectionFactory { _, url ->
            requests += url.path
            val body = when (url.path) {
                "/" -> html
                "/a41.js" -> script
                "/eportal/portal/page/loadConfig" -> config.replace("\"en_md5\":\"0\"", "\"en_md5\":\"1\"")
                else -> throw AssertionError("credentials must never be submitted")
            }
            Reply(url, 200, body.toByteArray())
        }
        try {
            PortalClient(factory).authenticate(campus, Credentials("fixture", "fixture-password"), Provider.CAMPUS)
            fail("unsupported configuration must stop")
        } catch (failure: PortalException) { assertEquals(PortalFailure.UNSUPPORTED, failure.reason) }
        assertFalse(requests.contains("/eportal/portal/login"))
    }

    @Test fun internetCheckRejectsRedirectsAndRequiresAnActualHttps204() = runTest {
        val seen = mutableListOf<NetworkSnapshot>()
        val redirected = PortalClient(TargetConnectionFactory { network, url ->
            seen += network
            Reply(url, 204, redirect = "https://yc.gxnu.edu.cn/")
        })
        assertFalse(redirected.verifyInternet(campus))
        assertEquals(listOf(campus, campus), seen)
        val captive = PortalClient(TargetConnectionFactory { _, url -> Reply(url, 200, "学校登录页".toByteArray()) })
        assertFalse(captive.verifyInternet(campus))
    }

    @Test fun internetCheckReturnsOnceThisWifiProvidesAnAuthenticatedHttps204() = runTest {
        val successfulNetworks = mutableListOf<NetworkSnapshot>()
        val successful = PortalClient(TargetConnectionFactory { network, url ->
            successfulNetworks += network
            Reply(url, 204)
        })
        assertTrue(successful.verifyInternet(campus))
        assertEquals(listOf(campus), successfulNetworks)
    }

    @Test fun blockedFirstProbeFallsBackOnTheSameWifiInsteadOfReportingOffline() = runTest {
        val seen = mutableListOf<NetworkSnapshot>()
        val client = PortalClient(TargetConnectionFactory { network, url ->
            seen += network
            Reply(url, if (seen.size == 1) 200 else 204)
        })
        assertTrue(client.verifyInternet(campus))
        assertEquals(listOf(campus, campus), seen)
    }

    @Test fun unreachableFirstProbeAllowsTheSecondProbeToEstablishInternetAccess() = runTest {
        val seen = mutableListOf<NetworkSnapshot>()
        val client = PortalClient(TargetConnectionFactory { network, url ->
            seen += network
            if (seen.size == 1) throw java.net.SocketTimeoutException("fixture timeout")
            Reply(url, 204)
        })
        assertTrue(client.verifyInternet(campus))
        assertEquals(listOf(campus, campus), seen)
    }

    /** 非 Wi-Fi（手机网络）没有可认证的链路，必须在发出任何请求之前就被拒。 */
    @Test fun aNonWifiNetworkNeverOpensAnyRequestWithOrWithoutCredentials() = runTest {
        val client = PortalClient(TargetConnectionFactory { _, _ -> throw AssertionError("no request allowed") })
        val cellular = NetworkSnapshot("cellular-fixture", "", isWifi = false)
        try {
            client.authenticate(cellular, Credentials("fixture", "fixture-password"), Provider.CAMPUS)
            fail("a network that is not Wi-Fi must be rejected")
        } catch (failure: PortalException) { assertEquals(PortalFailure.UNREACHABLE, failure.reason) }
        try {
            client.verifyInternet(cellular)
            fail("a network that is not Wi-Fi must be rejected")
        } catch (failure: PortalException) { assertEquals(PortalFailure.UNREACHABLE, failure.reason) }
    }

    /**
     * 名字读不出来的 Wi-Fi 要照常去问认证页 —— 学校换了 SSID、或本机没拿到定位权限时，
     * 名字就是读不出来，而这跟「这是不是校园网」是两件事。是否真的走通了由认证页说了算。
     */
    @Test fun aWifiWithNoReadableNameStillAsksTheSchoolPortal() = runTest {
        val seen = mutableListOf<NetworkSnapshot>()
        val client = PortalClient(TargetConnectionFactory { network, url ->
            seen += network
            Reply(url, if (seen.size == 1) 200 else 204)
        })
        val unnamed = campus.copy(ssid = "未识别 Wi-Fi")
        assertTrue("认证页说已在线，就该判定在线", client.verifyInternet(unnamed))
        assertEquals("请求必须真的带着这张网络发出去", listOf(unnamed, unnamed), seen)
    }

    @Test fun transportErrorsDoNotExposeCredentialsOrRequestUrl() = runTest {
        val password = "fixture-sensitive-value"
        val factory = TargetConnectionFactory { _, url ->
            if (url.path == "/eportal/portal/login") throw java.io.IOException(url.toString())
            val body = when (url.path) { "/" -> html; "/a41.js" -> script; else -> config }
            Reply(url, 200, body.toByteArray())
        }
        try {
            PortalClient(factory).authenticate(campus, Credentials("fixture-account", password), Provider.CAMPUS)
            fail("transport must report failure")
        } catch (failure: PortalException) {
            assertFalse(failure.toString().contains(password))
            assertFalse(failure.toString().contains("fixture-account"))
            assertFalse(failure.toString().contains("user_password"))
            assertNull(failure.cause)
        }
    }

    @Test fun terminalAddressUsesPageValuesIncludingQueryAndHexFallbackWithoutInventingMac() {
        val query = PortalDiscovery.discoverContext("<a href='/?UserIP=10.6.5.4&amp;UserV6IP=2001%3Adb8%3A%3A4'>link</a>")
        assertEquals("10.6.5.4", query.ipv4)
        assertEquals("2001:db8::4", query.ipv6)
        assertEquals("", query.mac)
        val fallback = PortalDiscovery.discoverContext("ss3='0a060504';ss4='01:23:45:67:89:ab';")
        assertEquals("10.6.5.4", fallback.ipv4)
        assertEquals("0123456789ab", fallback.mac)
    }

    @Test fun configurationRejectsExecutableWrapperAndAcceptsDocumentedMissingDefaults() {
        PortalDiscovery.validateConfiguration(config, script)
        try {
            PortalDiscovery.validateConfiguration(config + "alert('fixture')", script)
            fail("extra JavaScript must not be accepted as configuration")
        } catch (failure: PortalException) { assertEquals(PortalFailure.UNSUPPORTED, failure.reason) }
        try {
            PortalDiscovery.validateConfiguration(config, "var page_data_encrypt='1';")
            fail("encrypted page requires official portal")
        } catch (failure: PortalException) { assertEquals(PortalFailure.UNSUPPORTED, failure.reason) }
    }

    @Test fun cancellingInFlightCheckDisconnectsTargetConnectionAndNeverReportsOnline() = runBlocking {
        val enteredRead = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val client = PortalClient(TargetConnectionFactory { _, url ->
            object : Reply(url, 204) {
                override fun getResponseCode(): Int {
                    enteredRead.countDown()
                    check(disconnected.await(5, TimeUnit.SECONDS))
                    return 204
                }
                override fun disconnect() { disconnected.countDown() }
            }
        })
        var returnedOnline = false
        val job = launch { returnedOnline = client.verifyInternet(campus) }
        val started = withContext(Dispatchers.IO) { enteredRead.await(2, TimeUnit.SECONDS) }
        assertTrue("the test must cancel an actual pending request", started)
        job.cancel()
        assertTrue("cancellation must disconnect without waiting for read timeout", withContext(Dispatchers.IO) {
            disconnected.await(2, TimeUnit.SECONDS)
        })
        job.join()
        assertFalse(returnedOnline)
        assertTrue(job.isCancelled)
    }

    @Test fun captchaObjectAndBareJsonWithTrailingCodeCannotEnableLogin() {
        val captcha = config.replace("\"enable_alias\":\"0\"", "\"enable_alias\":\"0\",\"captcha\":{\"enabled\":true}")
        try {
            PortalDiscovery.validateConfiguration(captcha, script)
            fail("unknown interactive configuration must stop")
        } catch (failure: PortalException) { assertEquals(PortalFailure.UNSUPPORTED, failure.reason) }
        val raw = config.removePrefix("dr938(").removeSuffix(");") + "alert('fixture')"
        try {
            PortalDiscovery.validateConfiguration(raw, script)
            fail("raw JSON must consume the complete document")
        } catch (_: PortalException) { }
    }

    @Test fun readOnlyStatusFillsOnlyMissingValidTerminalFieldsAndIgnoresSavedAccountData() {
        val initial = cn.gxnu.campus.core.PortalContext(ipv4 = "", ipv6 = "2001:db8::42")
        val status = """dr1002({"result":0,"ss4":"02-23-45-67-89-ab","ss5":"10.9.8.7","uid":"fixture-other-user"});"""
        val updated = PortalDiscovery.mergeStatus(initial, status)
        assertEquals("10.9.8.7", updated.ipv4)
        assertEquals("2001:db8::42", updated.ipv6)
        assertEquals("0223456789ab", updated.mac)
        assertEquals("", PortalDiscovery.mergeStatus(initial, """{"result":0,"ss4":"invalid","ss5":"999.9.8.7"}""").mac)
        val existing = cn.gxnu.campus.core.PortalContext("10.1.2.3", mac = "0423456789ab")
        assertEquals(existing, PortalDiscovery.mergeStatus(existing, status))
    }
}
