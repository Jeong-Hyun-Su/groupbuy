package com.groupbuy.participation.domain

/** 도메인 포트. 구현은 infrastructure. */
interface ParticipationRepository {
    fun save(participation: Participation): Participation
    fun findById(id: Long): Participation?
    fun findByDealIdAndUserId(dealId: Long, userId: Long): Participation?

    /** 정원 계산 대상(RESERVED + CONFIRMED) 수. 딜 행 잠금 아래에서 호출해야 정확하다 (Phase 1) */
    fun countOccupying(dealId: Long): Int

    fun findAllByUserId(userId: Long): List<Participation>

    /** 마감 판정용. 확정(CONFIRMED) 참여만 — RESERVED 는 인원에서 제외한다 (ADR-07) */
    fun findConfirmed(dealId: Long): List<Participation>

    /** 선점 만료 스캐너용 (UC-06). 만료 시각이 지난 RESERVED 건 */
    fun findExpiredReservations(now: java.time.Instant, limit: Int): List<Participation>
}
