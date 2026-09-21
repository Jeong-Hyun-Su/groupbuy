package com.groupbuy.deal.domain

/** 설계서 2.4 딜 생명주기 */
enum class DealStatus {
    DRAFT,
    SCHEDULED,
    OPEN,
    CLOSING,    // 마감 처리 중. 중복 마감 방지용 중간 상태 (R8)
    SUCCEEDED,
    FAILED,
    SETTLED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == SETTLED || this == FAILED || this == CANCELLED
}
