package com.groupbuy.api.query

import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.participation.domain.ParticipantCountProvider
import com.groupbuy.participation.domain.ParticipationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 딜 상세 응답 (설계서 9.3). 여러 모듈을 조합하는 읽기 모델 — 모듈 안이 아니라 apps/api 에 둔다.
 * Phase 3 에서 CQRS 읽기 모델로 분리될 후보.
 */
data class DealDetailResponse(
    val id: Long,
    val title: String,
    val status: String,
    val listPrice: Int,
    val capacity: Int,
    val minParticipants: Int,
    val currentCount: Int,
    val projectedDiscountRate: Int,
    val projectedPrice: Int,
    val nextTier: NextTier?,
    val tiers: List<Tier>,
    val startAt: Instant,
    val closeAt: Instant,
    val finalParticipantCount: Int?,
    val finalDiscountRate: Int?,
    /** 요청자의 참여. X-User-Id 가 없거나 참여한 적이 없으면 null */
    val myParticipation: MyParticipation?,
) {
    data class Tier(val minCount: Int, val discountRate: Int)
    data class NextTier(val minCount: Int, val discountRate: Int, val remaining: Int)
    data class MyParticipation(val id: Long, val status: String, val reservationExpiresAt: Instant)
}

@Service
class DealDetailQuery(
    private val dealRepository: DealRepository,
    private val participantCountProvider: ParticipantCountProvider,
    private val participationRepository: ParticipationRepository,
) {

    @Transactional(readOnly = true)
    fun get(dealId: Long, userId: Long? = null): DealDetailResponse {
        val deal = dealRepository.findById(dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
        val count = participantCountProvider.currentCount(dealId)
        val next = deal.nextTierAfter(count)
        val mine = userId?.let { participationRepository.findByDealIdAndUserId(dealId, it) }

        return DealDetailResponse(
            id = deal.id!!,
            title = deal.title,
            status = deal.status.name,
            listPrice = deal.listPrice,
            capacity = deal.capacity,
            minParticipants = deal.minParticipants,
            currentCount = count,
            projectedDiscountRate = deal.projectedDiscountRate(count),
            projectedPrice = deal.projectedPrice(count),
            nextTier = next?.let { DealDetailResponse.NextTier(it.minCount, it.discountRate, it.minCount - count) },
            tiers = deal.tiers.map { DealDetailResponse.Tier(it.minCount, it.discountRate) },
            startAt = deal.startAt,
            closeAt = deal.closeAt,
            finalParticipantCount = deal.finalParticipantCount,
            finalDiscountRate = deal.finalDiscountRate,
            myParticipation = mine?.let { DealDetailResponse.MyParticipation(it.id!!, it.status.name, it.reservationExpiresAt) },
        )
    }
}
