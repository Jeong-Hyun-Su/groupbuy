package com.groupbuy.api.query

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.deal.domain.DealSort
import com.groupbuy.deal.domain.DealStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/deals")
class DealQueryController(
    private val dealDetailQuery: DealDetailQuery,
    private val dealListQuery: DealListQuery,
) {

    /** UC-03 딜 상세. X-User-Id 가 있으면 내 참여 상태를 함께 준다 (인증 도입 전 임시 헤더) */
    @GetMapping("/{dealId}")
    fun detail(
        @PathVariable dealId: Long,
        @RequestHeader("X-User-Id", required = false) userId: Long?,
    ): DealDetailResponse = dealDetailQuery.get(dealId, userId)

    /**
     * UC-02 딜 목록. Phase 1 은 DB 조회 — Phase 5 에서 search 모듈(Elasticsearch)로 이관한다.
     *
     * `status` 를 여러 번 넘기면 OR 로 묶인다. 기본은 진행 중인 딜(SCHEDULED, OPEN)만 보여준다.
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) status: List<DealStatus>?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "closing_soon") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): DealListResponse = dealListQuery.list(
        statuses = status ?: DEFAULT_STATUSES,
        keyword = q?.takeIf { it.isNotBlank() },
        sort = sort.toDealSort(),
        page = page,
        size = size,
    )

    private fun String.toDealSort(): DealSort = when (lowercase()) {
        "closing_soon" -> DealSort.CLOSING_SOON
        "latest" -> DealSort.LATEST
        // popular(인기순)는 참여 수 집계가 필요해 Phase 5 (ES) 로 미룬다
        else -> throw DomainException(ErrorCode.INVALID_REQUEST, "지원하지 않는 정렬입니다: $this")
    }

    companion object {
        private val DEFAULT_STATUSES = listOf(DealStatus.SCHEDULED, DealStatus.OPEN)
    }
}
