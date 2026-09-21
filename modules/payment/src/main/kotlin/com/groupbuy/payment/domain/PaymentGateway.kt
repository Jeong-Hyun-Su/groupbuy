package com.groupbuy.payment.domain

/**
 * PG 포트. 구현: TossPaymentGateway(Phase 1), FakePaymentGateway(테스트).
 * 모든 호출은 orderNo / idempotencyKey 로 멱등해야 한다.
 */
interface PaymentGateway {

    data class ApproveRequest(val paymentKey: String, val orderNo: String, val amount: Int)
    data class ApproveResult(val paymentKey: String, val approvedAmount: Int, val approvedAtIso: String)

    data class CancelRequest(val paymentKey: String, val cancelAmount: Int, val reason: String, val idempotencyKey: String)
    data class CancelResult(val cancelKey: String, val canceledAmount: Int)

    /** 결제 행에 기록할 PG 이름 (예: TOSS) */
    fun providerName(): String

    fun approve(request: ApproveRequest): ApproveResult
    fun cancel(request: CancelRequest): CancelResult

    /** 재시도 전 실제 상태 확인용 (Phase 3) */
    fun findByOrderNo(orderNo: String): ApproveResult?
}
