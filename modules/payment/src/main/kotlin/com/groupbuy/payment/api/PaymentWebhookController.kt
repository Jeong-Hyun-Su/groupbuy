package com.groupbuy.payment.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.groupbuy.common.error.DomainException
import com.groupbuy.payment.application.ConfirmPaymentCommand
import com.groupbuy.payment.application.PaymentConfirmService
import com.groupbuy.payment.application.WebhookSignatureVerifier
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * PG 웹훅 수신 (설계서 9.2).
 *
 * 응답 규약이 중요하다. PG 는 2xx 가 아니면 재전송하므로,
 *   - 우리 처리에 실패했다면 5xx 를 줘서 다시 받아야 하고
 *   - 우리가 관심 없는 이벤트나 이미 처리한 건이면 2xx 로 끝내야 한다.
 * 서명 실패만 4xx 로 거절한다 — 재전송받아도 통과할 리 없다.
 *
 * 본문은 원문 그대로 받아야 서명을 검증할 수 있다 (역직렬화 후 재직렬화하면 바이트가 달라진다).
 */
@RestController
@RequestMapping("/api/payments")
class PaymentWebhookController(
    private val paymentConfirmService: PaymentConfirmService,
    private val signatureVerifier: WebhookSignatureVerifier,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/webhook")
    fun webhook(
        @RequestBody rawBody: String,
        @RequestHeader(SIGNATURE_HEADER, required = false) signature: String?,
    ): ResponseEntity<Void> {
        signatureVerifier.verify(rawBody, signature)

        val payload = objectMapper.readTree(rawBody)
        val eventType = payload.path("eventType").asText()
        val data = payload.path("data")
        val status = data.path("status").asText()

        // 승인 완료 이벤트만 처리한다. 나머지는 확인했다는 뜻으로 200
        if (eventType != PAYMENT_STATUS_CHANGED || status != DONE) {
            log.debug("webhook ignored: eventType={} status={}", eventType, status)
            return ResponseEntity.ok().build()
        }

        val command = ConfirmPaymentCommand(
            paymentKey = data.path("paymentKey").asText(),
            orderNo = data.path("orderId").asText(),
            amount = data.path("totalAmount").asInt(),
        )

        return try {
            val result = paymentConfirmService.confirm(command)
            log.info("webhook processed: orderNo={} newlyApproved={}", command.orderNo, result.newlyApproved)
            ResponseEntity.ok().build()
        } catch (e: DomainException) {
            // 이미 승인됨·금액 불일치 등 도메인 판단이 끝난 건은 재전송받아도 결과가 같다
            log.warn("webhook rejected: orderNo={} code={} msg={}", command.orderNo, e.errorCode, e.message)
            ResponseEntity.ok().build()
        }
        // 그 외 예외는 그대로 던져 5xx → PG 가 재전송한다
    }

    companion object {
        const val SIGNATURE_HEADER = "TossPayments-Webhook-Signature"
        private const val PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED"
        private const val DONE = "DONE"
    }
}
