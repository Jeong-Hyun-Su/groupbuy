package com.groupbuy.api.query

import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.ParticipationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * UC-11 내 참여·환불 내역. participation + order + deal 을 조합하는 읽기 모델 — apps/api 에 둔다.
 * 환불 진행 상태는 payment 모듈이 생기면(Phase 1 후반) 여기에 합친다.
 */
data class MyParticipationResponse(
    val participationId: Long,
    val status: String,
    val reservationExpiresAt: Instant,
    val confirmedAt: Instant?,
    val deal: DealSummary,
    val order: OrderSummary,
) {
    data class DealSummary(val id: Long, val title: String, val status: String, val closeAt: Instant, val finalDiscountRate: Int?)
    data class OrderSummary(val orderNo: String, val status: String, val listAmount: Int, val finalAmount: Int?)
}

@Service
class MyParticipationsQuery(
    private val participationRepository: ParticipationRepository,
    private val orderRepository: OrderRepository,
    private val dealRepository: DealRepository,
) {

    @Transactional(readOnly = true)
    fun list(userId: Long): List<MyParticipationResponse> {
        val participations = participationRepository.findAllByUserId(userId)
        if (participations.isEmpty()) return emptyList()

        val orders = orderRepository.findAllByIds(participations.map { it.orderId }).associateBy { it.id!! }
        val deals = dealRepository.findAllByIds(participations.map { it.dealId }.toSet()).associateBy { it.id!! }

        return participations.mapNotNull { p ->
            val order = orders[p.orderId] ?: return@mapNotNull null
            val deal = deals[p.dealId] ?: return@mapNotNull null
            MyParticipationResponse(
                participationId = p.id!!,
                status = p.status.name,
                reservationExpiresAt = p.reservationExpiresAt,
                confirmedAt = p.confirmedAt,
                deal = MyParticipationResponse.DealSummary(deal.id!!, deal.title, deal.status.name, deal.closeAt, deal.finalDiscountRate),
                order = MyParticipationResponse.OrderSummary(order.orderNo, order.status.name, order.listAmount, order.finalAmount),
            )
        }
    }
}
