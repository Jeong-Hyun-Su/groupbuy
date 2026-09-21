package com.groupbuy.payment.api

import com.fasterxml.jackson.databind.JsonNode
import com.groupbuy.common.error.DomainException
import com.groupbuy.payment.application.PaymentConfirmService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * PG 웹훅 수신 (설계서 9.2).
 *
 * 토스는 결제 웹훅(`PAYMENT_STATUS_CHANGED`)에 서명 헤더를 주지 않는다 (서명은 payout·seller 웹훅뿐).
 * 그래서 본문을 믿지 않는다. orderId 만 꺼내 **PG 에 다시 물어본 결과**를 반영한다.
 *
 * 응답 규약: PG 는 2xx 가 아니면 재전송한다.
 *   - 관심 없는 이벤트, 이미 처리한 건, 도메인 판단이 끝난 건 → 2xx
 *   - PG 조회 실패·DB 오류 → 예외를 그대로 던져 5xx → 재전송받는다
 */
@RestController
@RequestMapping("/api/payments")
class PaymentWebhookController(
    private val paymentConfirmService: PaymentConfirmService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/webhook")
    fun webhook(@RequestBody payload: JsonNode): ResponseEntity<Void> {
        val eventType = payload.path("eventType").asText()
        val orderNo = payload.path("data").path("orderId").asText()

        if (eventType != PAYMENT_STATUS_CHANGED || orderNo.isBlank()) {
            log.debug("webhook ignored: eventType={}", eventType)
            return ResponseEntity.ok().build()
        }

        try {
            val result = paymentConfirmService.reconcile(orderNo)
            log.info("webhook processed: orderNo={} result={}", orderNo, result)
        } catch (e: DomainException) {
            // 없는 주문·금액 불일치·확정 불가(전액 환불됨) — 재전송받아도 결과가 같다
            log.warn("webhook rejected: orderNo={} code={} msg={}", orderNo, e.errorCode, e.message)
        }
        return ResponseEntity.ok().build()
    }

    companion object {
        private const val PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED"
    }
}
