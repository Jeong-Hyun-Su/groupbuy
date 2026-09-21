package com.groupbuy.deal.application

import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.common.time.TimeProvider
import com.groupbuy.deal.domain.Deal
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.deal.domain.DealStatus
import com.groupbuy.deal.domain.CloseResult
import com.groupbuy.deal.domain.DiscountPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class CreateDealCommand(
    val productId: Long,
    val sellerId: Long,
    val title: String,
    val listPrice: Int,
    val minParticipants: Int,
    val capacity: Int,
    val startAt: Instant,
    val closeAt: Instant,
    val tiers: List<DiscountPolicy.TierRule>,
)

@Service
class DealCommandService(
    private val dealRepository: DealRepository,
    private val timeProvider: TimeProvider,
) {

    /** UC-01 딜 등록. Phase 1 에서는 등록 즉시 SCHEDULED 로 둔다 (별도 승인 절차 없음) */
    @Transactional
    fun create(command: CreateDealCommand): Long {
        val deal = Deal.create(
            productId = command.productId,
            sellerId = command.sellerId,
            title = command.title,
            listPrice = command.listPrice,
            minParticipants = command.minParticipants,
            capacity = command.capacity,
            startAt = command.startAt,
            closeAt = command.closeAt,
            tiers = command.tiers,
        )
        deal.schedule()
        return dealRepository.save(deal).id!!
    }

    @Transactional
    fun cancel(dealId: Long, sellerId: Long) {
        val deal = load(dealId)
        if (deal.sellerId != sellerId) throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
        deal.cancel()
    }

    /** 스케줄러가 호출. SCHEDULED → OPEN */
    @Transactional
    fun openDueDeals(limit: Int = 100): Int {
        val now = timeProvider.now()
        val deals = dealRepository.findDueToOpen(now, limit)
        deals.forEach { it.open(now) }
        return deals.size
    }

    /** 마감 대상 조회. 스케줄러가 호출한다 */
    @Transactional(readOnly = true)
    fun findDueToClose(limit: Int = 100): List<Long> =
        dealRepository.findDueToClose(timeProvider.now(), limit).mapNotNull { it.id }

    /**
     * 마감 점유. OPEN → CLOSING (R8).
     * 여러 인스턴스가 동시에 시도해도 행 잠금 + 상태 전이로 하나만 성공한다.
     *
     * @return 이번 호출이 점유에 성공했으면 true
     */
    @Transactional
    fun beginClosing(dealId: Long): Boolean {
        val deal = dealRepository.findByIdForUpdate(dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
        if (deal.status != DealStatus.OPEN) return false     // 다른 인스턴스가 이미 가져갔다
        deal.beginClosing(timeProvider.now())
        return true
    }

    /** 마감 판정. CLOSING → SUCCEEDED | FAILED */
    @Transactional
    fun finishClosing(dealId: Long, confirmedCount: Int): CloseResult {
        val deal = load(dealId)
        return deal.finishClosing(confirmedCount, timeProvider.now())
    }

    /** 환불까지 끝난 성사 딜을 정산 대상으로 넘긴다. SUCCEEDED → SETTLED */
    @Transactional
    fun markSettled(dealId: Long) {
        load(dealId).markSettled()
    }

    private fun load(dealId: Long): Deal =
        dealRepository.findById(dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
}
