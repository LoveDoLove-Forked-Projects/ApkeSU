package me.weishu.kernelsu.ui.webmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManagerSecurityTest {
    @Test
    fun bearerTokenOnlyAcceptsBearerScheme() {
        assertTrue(WebManagerSecurity.bearerToken("Bearer secret") == "secret")
        assertTrue(WebManagerSecurity.bearerToken("bearer secret") == "secret")
        assertNull(WebManagerSecurity.bearerToken("Basic secret"))
        assertNull(WebManagerSecurity.bearerToken("Bearer"))
    }

    @Test
    fun tokenComparisonRejectsMissingOrDifferentValues() {
        assertTrue(WebManagerSecurity.constantTimeEquals("secret", "secret"))
        assertFalse(WebManagerSecurity.constantTimeEquals("secret", "secreT"))
        assertFalse(WebManagerSecurity.constantTimeEquals("secret", null))
    }

    @Test
    fun cookieTokenReadsOnlyTheExpectedCookie() {
        assertTrue(
            WebManagerSecurity.cookieToken("session=old; apkesu_web_token=secret; theme=dark") == "secret",
        )
        assertNull(WebManagerSecurity.cookieToken("apkesu_web_token=; theme=dark"))
        assertNull(WebManagerSecurity.cookieToken("other_apkesu_web_token=secret"))
    }

    @Test
    fun originPolicyAllowsLoopbackAndHeaderlessClients() {
        assertTrue(WebManagerSecurity.isAllowedOrigin(null, 10240))
        assertTrue(WebManagerSecurity.isAllowedOrigin("http://127.0.0.1:10240", 10240))
        assertTrue(WebManagerSecurity.isAllowedOrigin(" http://localhost:10240 ", 10240))
    }

    @Test
    fun originPolicyRejectsNullAndNonLoopbackOrigins() {
        assertFalse(WebManagerSecurity.isAllowedOrigin("null", 10240))
        assertFalse(WebManagerSecurity.isAllowedOrigin("http://localhost:10241", 10240))
        assertFalse(WebManagerSecurity.isAllowedOrigin("http://192.168.1.10:10240", 10240))
        assertFalse(WebManagerSecurity.isAllowedOrigin("", 10240))
    }

    @Test
    fun moduleIdValidationRejectsEncodedSeparatorsAndTraversal() {
        assertTrue(WebManagerSecurity.isValidModuleId("example.module-1"))
        assertFalse(WebManagerSecurity.isValidModuleId(""))
        assertFalse(WebManagerSecurity.isValidModuleId("../escape"))
        assertFalse(WebManagerSecurity.isValidModuleId("module/name"))
        assertFalse(WebManagerSecurity.isValidModuleId("a".repeat(129)))

        assertTrue(WebManagerSecurity.decodeModuleId("example.module-1") == "example.module-1")
        assertTrue(WebManagerSecurity.decodeModuleId("example%2Emodule-1") == "example.module-1")
        assertNull(WebManagerSecurity.decodeModuleId("module%2Fname"))
        assertNull(WebManagerSecurity.decodeModuleId("module%252Fname"))
        assertNull(WebManagerSecurity.decodeModuleId("..%2Fescape"))
    }
}
