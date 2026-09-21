package com.groupbuy.participation.application

import com.groupbuy.common.time.TimeProvider
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.ParticipationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 선점 만료 스캐너 (UC-06, 설계서 10.2).
 *
 * 결제하지 않고 떠난 사용자의 자리를 정원에 돌려준다. 이게 없으면 정원이 영영 차 있는 채로 마감된다.
 * Phase 1 은 단일 인스턴스 가정 — Phase 2 에서 `FOR UPDATE SKIP LOCKED` + Redis 선점 해제를 붙인다.
 */
@Service
class ReservationExpiryService(
    private val participationRepository: ParticipationRepository,
    private val orderRepository: OrderRepository,
    private val timeProvider: TimeProvider,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun expireOverdue(limit: Int = 500): Int {
        val now = timeProvider.now()
        val expired = participationRepository.findExpiredReservations(now, limit)

        expired.forEach { participation ->
            participation.expire(now)
            orderRepository.findById(participation.orderId)?.cancel()
        }
        if (expired.isNotEmpty()) log.info("expired {} reservations", expired.size)
        return expired.size
    }
}
