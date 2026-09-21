package com.groupbuy.payment.domain

/**
 * 웹훅 서명 검증용 시크릿을 읽는 포트. 구현은 infrastructure 의 설정 클래스.
 * application 이 PG 설정 형태를 몰라도 되게 한다.
 */
fun interface WebhookSecretProvider {
    /** 비어 있으면 검증을 건너뛴다 (로컬 전용) */
    fun secret(): String
}
