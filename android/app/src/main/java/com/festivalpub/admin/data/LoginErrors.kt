package com.festivalpub.admin.data

/**
 * Firebase Auth 로그인 실패를 스태프가 원인을 알 수 있는 한국어 문구로 바꾼다.
 * 끝에 에러 코드를 붙여, 콘솔을 보는 사람(동현)이 바로 원인을 찾을 수 있게 한다.
 *
 * @param code FirebaseAuthException.errorCode, 또는 NETWORK / TOO_MANY_REQUESTS / UNKNOWN
 * @param detail 서버가 준 원문 메시지 (API 키 차단·Auth 미설정은 코드 없이 이 문구로만 온다)
 */
fun loginErrorMessage(code: String, detail: String?): String {
    val d = detail.orEmpty()
    val text = when {
        code == "NETWORK" -> "네트워크 오류: 인터넷 연결을 확인하세요"
        code == "TOO_MANY_REQUESTS" || code == "ERROR_TOO_MANY_REQUESTS" ->
            "로그인 시도가 너무 많아 잠시 막혔습니다. 몇 분 뒤 다시 시도하세요"
        code == "ERROR_USER_NOT_FOUND" -> "등록되지 않은 계정입니다. 이메일을 확인하세요"
        code == "ERROR_WRONG_PASSWORD" -> "비밀번호가 틀렸습니다"
        // 이메일 열거 보호가 켜진 프로젝트는 '계정 없음'과 '비밀번호 틀림'을 구분하지 않는다
        code == "ERROR_INVALID_CREDENTIAL" || d.contains("INVALID_LOGIN_CREDENTIALS") ->
            "이메일 또는 비밀번호가 맞지 않습니다 (계정이 없거나 비밀번호가 틀림)"
        code == "ERROR_INVALID_EMAIL" -> "이메일 형식이 올바르지 않습니다"
        code == "ERROR_USER_DISABLED" -> "사용 중지된 계정입니다. Firebase 콘솔에서 계정 상태를 확인하세요"
        code == "ERROR_OPERATION_NOT_ALLOWED" || d.contains("OPERATION_NOT_ALLOWED") ->
            "이메일/비밀번호 로그인 방식이 꺼져 있습니다. Firebase 콘솔 → Authentication → 로그인 방법에서 켜야 합니다"
        d.contains("CONFIGURATION_NOT_FOUND") ->
            "이 Firebase 프로젝트에 Authentication 이 설정되지 않았습니다. 콘솔에서 Authentication 을 시작해야 합니다"
        d.contains("blocked", ignoreCase = true) || d.contains("API_KEY") || d.contains("API key", ignoreCase = true) ->
            "이 앱이 Firebase API 키 제한에 막혔습니다. 콘솔에서 앱의 SHA-1 인증서 지문과 API 키 제한을 확인하세요"
        else -> "로그인하지 못했습니다"
    }
    return "$text ($code)"
}

/** 로그에 남길 때 이메일 주소를 가린다 */
fun maskEmails(s: String): String = s.replace(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""), "[이메일]")
