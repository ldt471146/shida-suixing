package cn.gxnu.campus.core

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class PortalProtocolTest {
    private val context = PortalContext(
        ipv4 = "10.0.0.99", ipv6 = "2001:db8::8", mac = "001122334455",
        acIp = "10.0.0.1", acName = "sample-ac"
    )

    // Catches accidental legacy/Base64 authentication and password trimming.
    @Test fun mobileUsesSchoolRealmAndPreservesPassword() {
        val password = " a+b&c= 密码 "
        val parameters = PortalProtocol.loginParameters(
            Credentials(" student01 ", password), Provider.MOBILE, context
        )

        assertEquals(",1,student01@cmc", parameters["user_account"])
        assertEquals(password, parameters["user_password"])
        assertEquals("1", parameters["login_method"])
        assertEquals("2", parameters["terminal_type"])
        assertEquals("10.0.0.99", parameters["wlan_user_ip"])
        assertEquals("2001:db8::8", parameters["wlan_user_ipv6"])
        assertEquals("001122334455", parameters["wlan_user_mac"])
        assertEquals("10.0.0.1", parameters["wlan_ac_ip"])
        assertEquals("sample-ac", parameters["wlan_ac_name"])
        assertFalse(parameters.containsKey("DDDDD"))
        assertFalse(parameters.containsKey("upass"))
    }

    // Catches double-appending a realm after a user chooses another provider.
    @Test fun providerSelectionReplacesRecognizedAccountSuffixes() {
        val cases = listOf(
            Triple("student01@cmc", Provider.MOBILE, ",1,student01@cmc"),
            Triple("student01@ctc", Provider.UNICOM, ",1,student01@cuc"),
            Triple("student01@cuc@ctc", Provider.MOBILE, ",1,student01@cmc"),
            Triple("student01@GD", Provider.CAMPUS, ",1,student01"),
            Triple("student01@example.edu", Provider.TELECOM, ",1,student01@example.edu@ctc")
        )
        for ((account, provider, expected) in cases) {
            assertEquals(expected, PortalProtocol.loginParameters(
                Credentials(account, "sample-password"), provider, context
            )["user_account"])
        }
    }

    // Catches submitting an empty account or accidentally printing its password.
    @Test fun blankAccountsAreRejectedWithoutEchoingCredentials() {
        val secret = "fictional-secret"
        for (account in listOf("", "  \t ", "@cmc")) {
            val error = assertThrows(PortalException::class.java) {
                PortalProtocol.loginParameters(Credentials(account, secret), Provider.MOBILE, context)
            }
            assertEquals(PortalFailure.ACCOUNT, error.reason)
            assertFalse(error.toString().contains(secret))
        }
    }

    // Catches query injection, double encoding, and losing literal plus signs.
    @Test fun loginUrlRoundTripsSpecialPasswordAsOneField() {
        val password = " a+b&c= 密码 "
        val uri = URI(PortalProtocol.loginUrl(Credentials("student01", password), Provider.MOBILE, context))
        assertNotNull("The login URL must carry a query", uri.rawQuery)
        val fields = uri.rawQuery.split('&').map { it.split('=', limit = 2) }
        val decoded = fields.associate { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") }

        assertEquals("https", uri.scheme)
        assertEquals("yc.gxnu.edu.cn", uri.host)
        assertEquals(802, uri.port)
        assertEquals("/eportal/portal/login", uri.path)
        assertEquals(password, decoded["user_password"])
        assertEquals(",1,student01@cmc", decoded["user_account"])
        assertEquals(13, fields.size)
        assertFalse(decoded.containsKey("c"))
        assertTrue(uri.rawQuery.contains("user_password=+a%2Bb%26c%3D+%E5%AF%86%E7%A0%81+"))
    }

    // Catches treating a password's space as absence or replacing it with zero MACs.
    @Test fun spacePasswordAndUndiscoveredMacRemainLiteral() {
        val parameters = PortalProtocol.loginParameters(
            Credentials("sample", " "), Provider.CAMPUS, PortalContext("10.0.0.99")
        )
        assertEquals(" ", parameters["user_password"])
        assertEquals("", parameters["wlan_user_mac"])
    }

    // Catches mistaking metadata or echoed success text for protocol success.
    @Test fun responseUsesOnlyTopLevelResult() {
        assertTrue(PortalProtocol.parseResponse("""{"result":1,"ret_code":0,"msg":"success"}"""))
        assertTrue(PortalProtocol.parseResponse("""dr1003({"result":"ok","msg":"success"});"""))
        assertTrue(PortalProtocol.parseResponse(""" dr1003 ( {"result":"1"} ) ; """))
        assertFalse(PortalProtocol.parseResponse("""{"result":0,"msg":"success","data":{"result":1}}"""))
        assertFalse(PortalProtocol.parseResponse("""dr1003({"result":"fail","msg":"account error"});"""))
        assertFalse(PortalProtocol.parseResponse("""{"result":true}"""))
    }

    // Catches unsafe or lenient JSON/JSONP acceptance and duplicate-success injection.
    @Test fun nonDataAndMalformedResponsesAreRejectedSafely() {
        val invalid = listOf(
            "dr1003({\"result\":1});alert('fictional-secret')",
            "dr1003({\"result\":0});dr1003({\"result\":1})",
            "(()=>{return {result:1}})()",
            "window.dr1003({\"result\":1})",
            "dr1003({result:1})",
            "dr1003({'result':1})",
            "/* comment */ {\"result\":1}",
            "{\"result\":0,\"result\":1}",
            "{\"msg\":\"success\"}",
            "{\"result\":{\"value\":1}}",
            "{\"result\":1} {\"result\":0}",
            "[{\"result\":1}]",
            ""
        )
        for (payload in invalid) {
            val error = assertThrows("Unexpectedly accepted: $payload", PortalException::class.java) {
                PortalProtocol.parseResponse(payload)
            }
            assertEquals(PortalFailure.INVALID_RESPONSE, error.reason)
            assertFalse(error.toString().contains("fictional-secret"))
        }
    }
}
