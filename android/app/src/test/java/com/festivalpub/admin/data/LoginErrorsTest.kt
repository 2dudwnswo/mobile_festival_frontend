package com.festivalpub.admin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 로그인 실패 원인을 한국어로 구분해 보여주는지 (끝에 코드 표시) */
class LoginErrorsTest {

    private fun msg(code: String, detail: String? = null) = loginErrorMessage(code, detail)

    @Test fun `계정 없음과 비밀번호 틀림을 구분`() {
        assertTrue(msg("ERROR_USER_NOT_FOUND").startsWith("등록되지 않은 계정"))
        assertTrue(msg("ERROR_WRONG_PASSWORD").startsWith("비밀번호가 틀렸습니다"))
    }

    @Test fun `이메일 열거 보호가 켜진 프로젝트의 합쳐진 에러`() {
        assertTrue(msg("ERROR_INVALID_CREDENTIAL").contains("계정이 없거나 비밀번호가 틀림"))
        assertTrue(msg("UNKNOWN", "An internal error has occurred. [ INVALID_LOGIN_CREDENTIALS ]").contains("계정이 없거나"))
    }

    @Test fun `로그인 방식 비활성화`() {
        assertTrue(msg("ERROR_OPERATION_NOT_ALLOWED").contains("로그인 방법에서 켜야"))
    }

    @Test fun `네트워크 오류와 시도 과다`() {
        assertTrue(msg("NETWORK").startsWith("네트워크 오류"))
        assertTrue(msg("TOO_MANY_REQUESTS").contains("잠시 막혔습니다"))
        assertTrue(msg("ERROR_TOO_MANY_REQUESTS").contains("잠시 막혔습니다"))
    }

    @Test fun `코드 없이 서버 문구로만 오는 설정 문제`() {
        assertTrue(msg("UNKNOWN", "[ Requests from this Android client application com.festivalpub.admin are blocked. ]").contains("API 키 제한"))
        assertTrue(msg("UNKNOWN", "[ API key not valid. Please pass a valid API key. ]").contains("API 키 제한"))
        assertTrue(msg("UNKNOWN", "[ CONFIGURATION_NOT_FOUND ]").contains("Authentication 이 설정되지 않았습니다"))
    }

    @Test fun `이메일 형식·사용 중지 계정`() {
        assertTrue(msg("ERROR_INVALID_EMAIL").startsWith("이메일 형식"))
        assertTrue(msg("ERROR_USER_DISABLED").startsWith("사용 중지된 계정"))
    }

    @Test fun `모르는 에러도 코드를 붙여 보여준다`() {
        assertEquals("로그인하지 못했습니다 (ERROR_SOMETHING)", msg("ERROR_SOMETHING"))
        assertTrue(msg("ERROR_WRONG_PASSWORD").endsWith("(ERROR_WRONG_PASSWORD)"))
    }

    @Test fun `문구에 서버 원문(이메일 포함 가능)을 그대로 넣지 않는다`() {
        assertFalse(msg("UNKNOWN", "user a@b.com blocked").contains("a@b.com"))
    }

    @Test fun `로그용 이메일 가림`() {
        assertEquals("login [이메일] failed", maskEmails("login staff.01@example.co.kr failed"))
    }
}
