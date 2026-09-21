package com.groupbuy.participation.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.common.time.TimeProvider
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.ParticipationRepository
import com.groupbuy.participation.domain.ParticipationStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 승인된 결제를 참여에 반영한 결과 */
enum class ConfirmOutcome {
    CONFIRMED,
    ALREADY_CONFIRMED,
    /** 확정할 수 없다 (선점 만료·딜 마감·버려진 주문). 호출자는 승인분을 전액 환불해야 한다 (ADR-07) */
    REJECTED,
}

/**
 * 결제 승인에 따른 참여·주문 확정 (UC-04 6단계).
 * payment 모듈이 PG 승인 직후 같은 트랜잭션에서 호출한다.
 *
 * 확정 불가를 **예외로 알리지 않는다**. 이 시점엔 PG 가 이미 돈을 받았다 — 예외로 승인 기록 트랜잭션이
 * 롤백되면 결제 행조차 남지 않아 환불할 근거가 사라진다. 결과값으로 돌려주고 호출자가 환불한다.
 *
 * Phase 3 에서는 이 직접 호출을 `payment.approved` 이벤트 구독으로 바꾼다 (설계서 8.1).
 */
@Service
class ParticipationConfirmService(
    private val participationRepository: ParticipationRepository,
    private val orderRepository: OrderRepository,
    private val dealRepository: DealRepository,
    private val timeProvider: TimeProvider,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * PG 승인을 호출하기 **전** 검사. 확정될 수 없는 결제는 애초에 승인하지 않는다 — 승인이 없으면 환불도 없다.
     * 잠금 없이 읽으므로 최종 판단은 아니다. 이 검사와 승인 사이에 만료·마감되는 건은 `confirmByOrder` 가 걸러낸다.
     */
    @Transactional(readOnly = true)
    fun ensureConfirmable(orderId: Long) {
        val now = timeProvider.now()
        val order = orderRepository.findById(orderId) ?: throw NotFoundException(ErrorCode.ORDER_NOT_FOUND)
        val participation = participationRepository.findByDealIdAndUserId(order.dealId, order.userId)
            ?: throw NotFoundException(ErrorCode.PARTICIPATION_NOT_FOUND)
        val deal = dealRepository.findById(order.dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)

        if (participation.orderId == orderId && participation.status == ParticipationStatus.CONFIRMED) return
        if (participation.orderId != orderId || participation.status != ParticipationStatus.RESERVED ||
            participation.isReservationExpired(now)
        ) {
            throw DomainException(ErrorCode.RESERVATION_EXPIRED)
        }
        deal.ensureAcceptsParticipation(now)
    }

    /**
     * 주문에 딸린 참여를 CONFIRMED 로 올린다.
     *
     * 딜 행을 잠근다 — 마감 점유(`beginClosing`)와 같은 잠금이라 순서가 하나로 정해진다.
     * 마감보다 먼저 커밋되면 확정 인원에 들어가고, 늦으면 CLOSING 을 보고 REJECTED 가 된다.
     * 잠그지 않으면 "마감 집계 직후에 CONFIRMED 가 된" 참여가 생겨 환불도 정산도 못 받는다.
     */
    @Transactional
    fun confirmByOrder(orderId: Long): ConfirmOutcome {
        val now = timeProvider.now()
        val order = orderRepository.findById(orderId) ?: throw NotFoundException(ErrorCode.ORDER_NOT_FOUND)
        val deal = dealRepository.findByIdForUpdate(order.dealId) ?: throw NotFoundException(ErrorCode.DEAL_NOT_FOUND)
        val participation = participationRepository.findByDealIdAndUserId(order.dealId, order.userId)
            ?: throw NotFoundException(ErrorCode.PARTICIPATION_NOT_FOUND)

        // 재선점으로 주문이 갈린 경우 — 이 주문은 이미 버려진 것이다
        if (participation.orderId != orderId) {
            log.warn("stale order approved: orderId={} participationOrderId={}", orderId, participation.orderId)
            return ConfirmOutcome.REJECTED
        }
        if (participation.status == ParticipationStatus.CONFIRMED) return ConfirmOutcome.ALREADY_CONFIRMED

        if (participation.status != ParticipationStatus.RESERVED || participation.isReservationExpired(now) ||
            !deal.acceptsParticipation(now)
        ) {
            log.warn(
                "approved but not confirmable: orderId={} participation={} deal={}",
                orderId, participation.status, deal.status,
            )
            return ConfirmOutcome.REJECTED
        }

        participation.confirm(now)
        order.markPaid()
        return ConfirmOutcome.CONFIRMED
    }
}
