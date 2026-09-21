package com.groupbuy.participation.api

import com.groupbuy.participation.application.ParticipationCommandService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 참여 API. 설계서 9.2.
 * 인증은 Phase 1 범위 밖 — 임시로 X-User-Id 헤더를 쓴다. JWT 도입 시 제거.
 */
@RestController
@RequestMapping("/api/deals/{dealId}/participations")
class ParticipationController(
    private val participationCommandService: ParticipationCommandService,
) {

    data class ParticipateResponse(
        val participationId: Long,
        val orderNo: String,
        val orderName: String,
        val amount: Int,
        val reservationExpiresAt: Instant,
        val currentCount: Int,
    )

    /** UC-04 자리 선점 + 주문 생성. 응답으로 결제창을 연다 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun participate(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable dealId: Long,
    ): ParticipateResponse {
        val result = participationCommandService.participate(dealId = dealId, userId = userId)
        return ParticipateResponse(
            participationId = result.participationId,
            orderNo = result.orderNo,
            orderName = result.orderName,
            amount = result.amount,
            reservationExpiresAt = result.reservationExpiresAt,
            currentCount = result.currentCount,
        )
    }
}
