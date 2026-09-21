package com.groupbuy.participation.domain

/** 설계서 2.5 참여 생명주기 */
enum class ParticipationStatus {
    RESERVED,    // 자리 선점, 결제 대기 (TTL)
    CONFIRMED,   // 결제 승인
    EXPIRED,     // 결제 제한시간 초과
    CANCELLED,   // 사용자 자진 취소 (OPEN 중)
    ADJUSTING,   // 딜 성사 → 차액 환불 진행
    FINALIZED,   // 차액 환불 완료
    REFUNDING,   // 딜 무산 → 전액 환불 진행
    REFUNDED;    // 전액 환불 완료

    /** 정원 계산에 포함되는 상태 */
    val occupiesSlot: Boolean
        get() = this == RESERVED || this == CONFIRMED
}
