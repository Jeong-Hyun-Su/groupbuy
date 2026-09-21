package com.groupbuy.participation.domain

/**
 * 딜의 현재 인원(선점 + 확정). 딜 상세·예상 할인율 계산에 쓴다.
 * Phase 1: DB count / Phase 2: Redis `deal:{id}:count`
 */
interface ParticipantCountProvider {
    fun currentCount(dealId: Long): Int
}
