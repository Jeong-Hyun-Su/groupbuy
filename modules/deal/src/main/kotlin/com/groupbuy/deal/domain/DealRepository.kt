package com.groupbuy.deal.domain

import java.time.Instant

/** 도메인 포트. 구현은 infrastructure. */
interface DealRepository {
    fun save(deal: Deal): Deal
    fun findById(id: Long): Deal?

    /**
     * 행 잠금 조회 (SELECT ... FOR UPDATE). 같은 딜에 대한 참여 요청을 직렬화해 정원 카운팅을 보호한다.
     * Phase 1 전용 — LT-01 에서 락 대기가 병목으로 드러나면 Phase 2 에서 Redis Lua 로 대체한다 (ADR-04).
     */
    fun findByIdForUpdate(id: Long): Deal?

    fun findAllByIds(ids: Collection<Long>): List<Deal>
    fun findDueToOpen(now: Instant, limit: Int): List<Deal>
    fun findDueToClose(now: Instant, limit: Int): List<Deal>

    /**
     * 목록 조회 (UC-02). Phase 1 은 DB 직접 조회 — Phase 5 에서 Elasticsearch 로 옮긴다.
     *
     * @param statuses 비어 있으면 전체
     * @param keyword  제목 부분 일치. null 이면 전체
     */
    fun search(statuses: Collection<DealStatus>, keyword: String?, sort: DealSort, offset: Int, limit: Int): List<Deal>

    fun countSearch(statuses: Collection<DealStatus>, keyword: String?): Long
}

/** 목록 정렬 기준 (설계서 9.2) */
enum class DealSort {
    /** 마감 임박순 — 마감이 가까운 딜이 위로 */
    CLOSING_SOON,

    /** 최신순 */
    LATEST,
}
