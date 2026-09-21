package com.groupbuy.participation.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.common.time.TimeProvider
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.participation.domain.Order
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.Participation
import com.groupbuy.participation.domain.ParticipationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 선점 결과. 클라이언트는 이 값으로 PG 결제창을 연다 (설계서 9.2) */
data class ParticipateResult(
    val participationId: Long,
    val orderNo: String,
    val orderName: String,
    val amount: Int,
    val reservationExpiresAt: Instant,
    /** 선점 직후 인원 (선점 + 확정). 예상 할인율 표시용 */
    val currentCount: Int,
)

@Service
class ParticipationCommandService(
    private val dealRepository: DealRepository,
    private val participationRepository: ParticipationRepository,
    private val orderRepository: OrderRepository,
    private val timeProvider: TimeProvider,
    private val properties: ParticipationProperties,
) {

    /**
     * UC-04 기본 흐름 1~3단계: 중복 검사 → 정원 검사 → 선점 + 주문 생성.
     *
     * Phase 1 동시성 전략: 딜 행을 `FOR UPDATE` 로 잠가 같은 딜에 대한 요청을 직렬화한다.
     * 검사(중복·정원)와 변경(insert)이 한 잠금 안에서 일어나므로 R1·R2 가 지켜진다.
     * 딜 하나에 요청이 몰리면 락 대기가 곧 병목이다 — LT-01 에서 측정해 Phase 2(Redis Lua) 의 동기로 삼는다.
     */
    @Transactional
    fun participate(dealId: Long, userId: Long): ParticipateResult {
        val now = timeProvider.now()
        val deal = dealRepository.findByIdForUpdate(dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
        deal.ensureAcceptsParticipation(now)

        // R2: 딜당 사용자당 1건. 만료·취소된 행만 재선점을 허용한다
        val existing = participationRepository.findByDealIdAndUserId(dealId, userId)
        if (existing != null && !existing.canReserveAgain) throw DomainException(ErrorCode.DUPLICATE_PARTICIPATION)

        // R1: 선점 + 확정 인원이 정원을 넘지 않는다
        val occupied = participationRepository.countOccupying(dealId)
        if (occupied >= deal.capacity) throw DomainException(ErrorCode.DEAL_FULL)

        val order = orderRepository.save(Order.create(userId = userId, dealId = dealId, listAmount = deal.listPrice))
        val orderId = order.id ?: throw IllegalStateException("주문 저장 후 id 가 없습니다.")
        val ttl = properties.reservationTtl

        val participation = if (existing == null) {
            participationRepository.save(Participation.reserve(dealId, userId, orderId, now, ttl))
        } else {
            existing.apply { reserveAgain(orderId, now, ttl) }
        }

        return ParticipateResult(
            participationId = participation.id ?: throw IllegalStateException("참여 저장 후 id 가 없습니다."),
            orderNo = order.orderNo,
            orderName = deal.title,
            amount = order.listAmount,
            reservationExpiresAt = participation.reservationExpiresAt,
            currentCount = occupied + 1,
        )
    }
}
