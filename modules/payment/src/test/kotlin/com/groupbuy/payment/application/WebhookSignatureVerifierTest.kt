package com.groupbuy.payment.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookSignatureVerifierTest {

    private val secret = "whsec_test_1234567890"
    private val body = """{"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"GB-1-42-abc","status":"DONE"}}"""

    private fun verifier(withSecret: String = secret) =
        WebhookSignatureVerifier({ withSecret })

    private fun sign(payload: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        }
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `올바른 서명은 통과한다`() {
        assertThatCode { verifier().verify(body, sign(body, secret)) }.doesNotThrowAnyException()
    }

    @Test
    fun `본문이 한 글자라도 다르면 거부한다`() {
        val tampered = body.replace("DONE", "CANCELED")

        assertThatThrownBy { verifier().verify(tampered, sign(body, secret)) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID)
    }

    @Test
    fun `다른 시크릿으로 만든 서명은 거부한다`() {
        assertThatThrownBy { verifier().verify(body, sign(body, "whsec_attacker")) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID)
    }

    @Test
    fun `서명 헤더가 없으면 거부한다`() {
        assertThatThrownBy { verifier().verify(body, null) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID)
    }

    @Test
    fun `시크릿이 설정되지 않은 로컬 환경에서는 검증을 건너뛴다`() {
        assertThatCode { verifier(withSecret = "").verify(body, null) }.doesNotThrowAnyException()
    }
}
