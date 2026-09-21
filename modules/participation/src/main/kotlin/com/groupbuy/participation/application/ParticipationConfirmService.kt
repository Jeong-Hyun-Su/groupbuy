package com.groupbuy.participation.application

import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.common.time.TimeProvider
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.ParticipationRepository
import com.groupbuy.participation.domain.ParticipationStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 결제 승인에 따른 참여·주문 확정 (UC-04 6단계).
 * payment 모듈이 PG 승인 직후 같은 트랜잭션에서 호출한다.
 *
 * Phase 3 에서는 이 직접 호출을 `payment.approved` 이벤트 구독으로 바꾼다 (설계서 8.1).
 */
@Service
class ParticipationConfirmService(
    private val participationRepository: ParticipationRepository,
    private val orderRepository: OrderRepository,
    private val timeProvider: TimeProvider,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주문에 딸린 참여를 CONFIRMED 로 올린다.
     *
     * @return 이번 호출로 상태가 바뀌었으면 true. 이미 확정된 건이면 false (멱등)
     */
    @Transactional
    fun confirmByOrder(orderId: Long): Boolean {
        val order = orderRepository.findById(orderId) ?: throw NotFoundException(ErrorCode.ORDER_NOT_FOUND)
        val participation = participationRepository.findByDealIdAndUserId(order.dealId, order.userId)
            ?: throw NotFoundException(ErrorCode.PARTICIPATION_NOT_FOUND)

        // 재선점으로 주문이 갈린 경우 — 이 주문은 이미 버려진 것이다. 승인분은 전액 환불 대상 (Phase 3)
        if (participation.orderId != orderId) {
            log.warn("stale order approved: orderId={} participationOrderId={}", orderId, participation.orderId)
            return false
        }
        if (participation.status == ParticipationStatus.CONFIRMED) return false

        participation.confirm(timeProvider.now())
        order.markPaid()
        return true
    }
}
