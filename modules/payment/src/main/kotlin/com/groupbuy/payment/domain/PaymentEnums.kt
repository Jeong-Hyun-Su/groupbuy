package com.groupbuy.payment.domain

enum class PaymentStatus {
    READY,                // 주문 생성됨, 승인 전
    APPROVED,             // PG 승인 완료
    PARTIALLY_CANCELED,   // 차액 환불 완료 (Phase 3)
    CANCELED,             // 전액 취소 완료
    FAILED;               // 승인 실패

    /** 환불(취소) 대상이 될 수 있는가 */
    val cancellable: Boolean
        get() = this == APPROVED || this == PARTIALLY_CANCELED
}

/** 설계서 2.6 환불 생명주기 */
enum class RefundStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    MANUAL_REQUIRED,
}

enum class RefundReason {
    TIER_ADJUST,                   // 성사 → 차액 환불
    DEAL_FAILED,                   // 무산 → 전액 환불
    USER_CANCEL,                   // 자진 취소
    DEAL_CLOSED_DURING_PAYMENT,    // 마감 직후 승인된 건 전액 취소 (ADR-07)
}
