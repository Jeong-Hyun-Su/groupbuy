package com.groupbuy.payment.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import org.slf4j.LoggerFactory
import com.groupbuy.payment.domain.WebhookSecretProvider
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 웹훅 서명 검증. 본문 원문을 시크릿으로 HMAC-SHA256 한 값과 헤더를 비교한다.
 *
 * 서명이 없으면 누구나 "결제 승인됐다"는 요청을 보낼 수 있다. 검증은 선택이 아니다.
 * 다만 시크릿이 비어 있으면(로컬 개발) 검증을 건너뛰고 경고만 남긴다.
 */
@Component
class WebhookSignatureVerifier(
    private val webhookSecretProvider: WebhookSecretProvider,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(rawBody: String, signature: String?) {
        val secret = webhookSecretProvider.secret()
        if (secret.isBlank()) {
            log.warn("webhook secret 이 비어 있어 서명 검증을 건너뛴다. 운영 환경에서는 반드시 설정할 것")
            return
        }
        if (signature.isNullOrBlank()) throw DomainException(ErrorCode.WEBHOOK_SIGNATURE_INVALID, "서명 헤더가 없습니다.")

        val expected = sign(rawBody, secret)
        // 타이밍 공격을 막으려면 길이·내용 비교가 상수 시간이어야 한다
        if (!MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), signature.toByteArray(StandardCharsets.UTF_8))) {
            throw DomainException(ErrorCode.WEBHOOK_SIGNATURE_INVALID)
        }
    }

    private fun sign(rawBody: String, secret: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM))
        }
        return Base64.getEncoder().encodeToString(mac.doFinal(rawBody.toByteArray(StandardCharsets.UTF_8)))
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
    }
}
