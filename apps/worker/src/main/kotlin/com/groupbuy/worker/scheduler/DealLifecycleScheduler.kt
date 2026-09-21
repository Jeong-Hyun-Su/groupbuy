package com.groupbuy.worker.scheduler

import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.participation.application.ReservationExpiryService
import com.groupbuy.worker.closing.DealClosingOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 설계서 10.3 마감 트리거 (ADR-05: DB 폴링).
 *
 * Phase 1: 단순 폴링. 마감 점유는 행 잠금 + CLOSING 전이로 이미 중복을 막는다 (R8).
 * Phase 3: FOR UPDATE SKIP LOCKED 로 여러 인스턴스가 서로 다른 딜을 나눠 갖게 만든다.
 */
@Component
class DealLifecycleScheduler(
    private val dealCommandService: DealCommandService,
    private val dealClosingOrchestrator: DealClosingOrchestrator,
    private val reservationExpiryService: ReservationExpiryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${groupbuy.scheduler.open-interval-ms:10000}")
    fun openDueDeals() {
        val opened = dealCommandService.openDueDeals()
        if (opened > 0) log.info("opened {} deals", opened)
    }

    @Scheduled(fixedDelayString = "\${groupbuy.scheduler.close-interval-ms:5000}")
    fun closeDueDeals() {
        val reports = dealClosingOrchestrator.closeDueDeals()
        reports.filter { it.closed }.forEach {
            log.info(
                "closed deal {}: {} (refunded {}/{})",
                it.dealId, it.result?.status, it.refundedCount, it.refundedCount + it.failedCount,
            )
        }
    }

    /** 선점 만료 스캐너 (UC-06). 결제하지 않고 떠난 자리를 정원에 돌려준다 */
    @Scheduled(fixedDelayString = "\${groupbuy.scheduler.reservation-expiry-interval-ms:10000}")
    fun expireReservations() {
        val expired = reservationExpiryService.expireOverdue()
        if (expired > 0) log.info("expired {} reservations", expired)
    }
}
