package com.groupbuy.api.query

import com.groupbuy.deal.domain.Deal
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.deal.domain.DealSort
import com.groupbuy.deal.domain.DealStatus
import com.groupbuy.participation.domain.ParticipantCountProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** UC-02 딜 목록 (설계서 9.2) */
data class DealListResponse(
    val items: List<Item>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
) {
    data class Item(
        val id: Long,
        val title: String,
        val status: String,
        val listPrice: Int,
        val capacity: Int,
        val minParticipants: Int,
        val currentCount: Int,
        val projectedDiscountRate: Int,
        val projectedPrice: Int,
        val closeAt: Instant,
    )
}

/**
 * 딜 목록 조회. deal + participation 을 조합하는 읽기 모델이라 apps/api 에 둔다.
 *
 * **Phase 1 의 의도된 병목**: 인원을 딜마다 따로 센다. 20건 목록이면 count 쿼리가 20번 나간다 (N+1).
 * LT-02 에서 이 지점이 먼저 무너질 것이고, Phase 2 에서 Redis 카운터로 한 번에 읽어 해소한다.
 */
@Service
class DealListQuery(
    private val dealRepository: DealRepository,
    private val participantCountProvider: ParticipantCountProvider,
) {

    @Transactional(readOnly = true)
    fun list(
        statuses: Collection<DealStatus>,
        keyword: String?,
        sort: DealSort,
        page: Int,
        size: Int,
    ): DealListResponse {
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)

        val deals = dealRepository.search(statuses, keyword, sort, safePage * safeSize, safeSize)
        val total = dealRepository.countSearch(statuses, keyword)

        return DealListResponse(
            items = deals.map { it.toItem() },
            page = safePage,
            size = safeSize,
            totalCount = total,
        )
    }

    private fun Deal.toItem(): DealListResponse.Item {
        val count = participantCountProvider.currentCount(id!!)
        return DealListResponse.Item(
            id = id!!,
            title = title,
            status = status.name,
            listPrice = listPrice,
            capacity = capacity,
            minParticipants = minParticipants,
            currentCount = count,
            projectedDiscountRate = projectedDiscountRate(count),
            projectedPrice = projectedPrice(count),
            closeAt = closeAt,
        )
    }

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
