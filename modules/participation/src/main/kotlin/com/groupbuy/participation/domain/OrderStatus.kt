package com.groupbuy.participation.domain

/** 주문 상태. 참여(자리)와 별개로 "돈"의 관점에서 본 생명주기 */
enum class OrderStatus {
    READY,       // 주문 생성, 결제 대기
    PAID,        // 정가 결제 승인
    FINALIZED,   // 마감 후 확정 금액 반영 (차액 환불 대상)
    CANCELED,    // 선점 만료 · 자진 취소 · 무산 (전액 환불 대상)
}
