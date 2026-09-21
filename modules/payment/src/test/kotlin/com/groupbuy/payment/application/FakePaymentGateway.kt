package com.groupbuy.payment.application

import com.groupbuy.payment.domain.PaymentGateway
import com.groupbuy.payment.domain.PaymentGatewayException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 테스트용 PG. 설계서 14장 리스크 대응 — 토스 테스트 환경에서 재현하기 어려운
 * 타임아웃·거절 시나리오를 여기서 만든다 (Phase 3 의 WireMock 대체재).
 */
class FakePaymentGateway : PaymentGateway {

    val approveCalls = AtomicInteger()
    val cancelCalls = AtomicInteger()

    /** 승인 호출마다 이 예외를 던진다. null 이면 정상 승인 */
    var approveFailure: PaymentGatewayException? = null

    /** 승인된 orderNo → 결과 */
    private val approvals = mutableMapOf<String, PaymentGateway.ApproveResult>()

    /** "PG 는 승인했는데 우리는 모르는" 상태를 만든다 — 승인 응답 유실, 프로세스 사망 */
    fun seedApproved(orderNo: String, paymentKey: String, amount: Int) {
        approvals[orderNo] = PaymentGateway.ApproveResult(paymentKey, amount, "2026-09-20T11:00:00+09:00")
    }

    override fun providerName(): String = "FAKE"

    override fun approve(request: PaymentGateway.ApproveRequest): PaymentGateway.ApproveResult {
        approveCalls.incrementAndGet()
        approveFailure?.let { throw it }

        val result = PaymentGateway.ApproveResult(
            paymentKey = request.paymentKey,
            approvedAmount = request.amount,
            approvedAtIso = "2026-09-20T11:00:00+09:00",
        )
        approvals[request.orderNo] = result
        return result
    }

    override fun cancel(request: PaymentGateway.CancelRequest): PaymentGateway.CancelResult {
        cancelCalls.incrementAndGet()
        return PaymentGateway.CancelResult(cancelKey = "cancel-${request.idempotencyKey}", canceledAmount = request.cancelAmount)
    }

    override fun findByOrderNo(orderNo: String): PaymentGateway.ApproveResult? = approvals[orderNo]
}
