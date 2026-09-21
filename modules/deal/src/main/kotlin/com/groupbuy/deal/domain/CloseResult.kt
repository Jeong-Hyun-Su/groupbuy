package com.groupbuy.deal.domain

/** 마감 판정 결과. 설계서 10.3 */
data class CloseResult(
    val dealId: Long,
    val status: DealStatus,          // SUCCEEDED or FAILED
    val finalParticipantCount: Int,
    val finalDiscountRate: Int,      // FAILED 이면 0
    val finalPrice: Int,             // FAILED 이면 listPrice (전액 환불 기준)
) {
    val succeeded: Boolean get() = status == DealStatus.SUCCEEDED
}
