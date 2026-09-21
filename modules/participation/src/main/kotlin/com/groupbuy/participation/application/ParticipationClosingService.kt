package com.groupbuy.participation.application

import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.ParticipationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 마감 시점의 확정 참여 1건 — 환불 대상이 된다 */
data class ConfirmedParticipant(
    val participationId: Long,
    val userId: Long,
    val orderId: Long,
    val listAmount: Int,
)

/**
 * 마감에 따른 참여·주문 일괄 전이 (설계서 10.3).
 * 마감 오케스트레이터(apps/worker)가 호출한다.
 */
@Service
class ParticipationClosingService(
    private val participationRepository: ParticipationRepository,
    private val orderRepository: OrderRepository,
) {

    /** 확정 인원. 마감 판정의 입력이다 — RESERVED 는 세지 않는다 (ADR-07) */
    @Transactional(readOnly = true)
    fun confirmedParticipants(dealId: Long): List<ConfirmedParticipant> {
        val participations = participationRepository.findConfirmed(dealId)
        if (participations.isEmpty()) return emptyList()

        val orders = orderRepository.findAllByIds(participations.map { it.orderId }).associateBy { it.id!! }
        return participations.map { p ->
            val order = orders[p.orderId]
                ?: throw NotFoundException(ErrorCode.ORDER_NOT_FOUND, "참여의 주문을 찾을 수 없습니다: participationId=${p.id}")
            ConfirmedParticipant(
                participationId = p.id!!,
                userId = p.userId,
                orderId = p.orderId,
                listAmount = order.listAmount,
            )
        }
    }

    /**
     * 성사: 주문에 확정 금액을 반영하고 참여를 ADJUSTING 으로 올린다.
     * 차액 환불은 호출자가 payment 모듈로 실행한다.
     */
    @Transactional
    fun markAdjusting(participationId: Long, finalAmount: Int): Int {
        val participation = participationRepository.findById(participationId)
            ?: throw NotFoundException(ErrorCode.PARTICIPATION_NOT_FOUND)
        val order = orderRepository.findById(participation.orderId)
            ?: throw NotFoundException(ErrorCode.ORDER_NOT_FOUND)

        order.finalize(finalAmount)
        participation.startAdjusting()
        return order.tierRefundAmount()
    }

    /** 무산: 주문을 취소하고 참여를 REFUNDING 으로 올린다. 전액 환불 대상 (R5) */
    @Transactional
    fun markRefunding(participationId: Long): Int {
        val participation = participationRepository.findById(participationId)
            ?: throw NotFoundException(ErrorCode.PARTICIPATION_NOT_FOUND)
        val order = orderRepository.findById(participation.orderId)
            ?: throw NotFoundException(ErrorCode.ORDER_NOT_FOUND)

        order.cancel()
        participation.startRefunding()
        return order.listAmount
    }

    /** 환불 완료 반영 */
    @Transactional
    fun markRefundSettled(participationId: Long, succeeded: Boolean) {
        val participation = participationRepository.findById(participationId)
            ?: throw NotFoundException(ErrorCode.PARTICIPATION_NOT_FOUND)
        if (!succeeded) return

        when (participation.status) {
            com.groupbuy.participation.domain.ParticipationStatus.ADJUSTING -> participation.finalize()
            com.groupbuy.participation.domain.ParticipationStatus.REFUNDING -> participation.markRefunded()
            else -> Unit
        }
    }
}
