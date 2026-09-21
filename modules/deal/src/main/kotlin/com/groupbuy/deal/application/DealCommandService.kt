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
     * 이미 CLOSING 이면 재개로 본다. 판정·환불 트랜잭션이 롤백되면 딜은 CLOSING 에 남는데,
     * 여기서 false 를 주면 그 딜은 영영 마감되지 않는다. 중복 판정은 `finishClosing` 의 행 잠금이 막는다.
     *
     * @return 판정을 진행해도 되면 true (새로 점유했거나, 중단된 마감의 재개)
     */
    @Transactional
    fun beginClosing(dealId: Long): Boolean {
        val deal = dealRepository.findByIdForUpdate(dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
        if (deal.status == DealStatus.CLOSING) return true    // 중단된 마감의 재개
        if (deal.status != DealStatus.OPEN) return false      // 이미 판정이 끝났다
        deal.beginClosing(timeProvider.now())
        return true
    }

    /**
     * 마감 판정. CLOSING → SUCCEEDED | FAILED.
     * 행을 잠근다 — 재개 경로로 두 인스턴스가 동시에 들어와도 뒤쪽은 앞쪽 커밋을 기다렸다가 상태 전이에서 걸린다 (R8).
     */
    // ponytail: 뒤쪽 인스턴스가 앞쪽의 환불이 끝날 때까지 행 잠금에서 대기한다. Phase 3 에서 SKIP LOCKED + closing_started_at 타임아웃으로 교체
    @Transactional
    fun finishClosing(dealId: Long, confirmedCount: Int): CloseResult {
        val deal = dealRepository.findByIdForUpdate(dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
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
