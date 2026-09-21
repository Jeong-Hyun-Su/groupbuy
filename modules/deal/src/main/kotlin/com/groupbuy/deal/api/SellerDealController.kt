package com.groupbuy.deal.api

import com.groupbuy.deal.application.CreateDealCommand
import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.DiscountPolicy
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 판매자 딜 API. 설계서 9.2.
 * 인증은 Phase 1 범위 밖 — 임시로 X-Seller-Id 헤더를 쓴다. JWT 도입 시 제거.
 */
@RestController
@RequestMapping("/api/seller/deals")
class SellerDealController(
    private val dealCommandService: DealCommandService,
) {

    data class TierRequest(
        @field:Min(1) val minCount: Int,
        @field:Min(0) val discountRate: Int,
    )

    data class CreateDealRequest(
        @field:Min(1) val productId: Long,
        @field:NotBlank val title: String,
        @field:Min(0) val listPrice: Int,
        @field:Min(1) val minParticipants: Int,
        @field:Min(1) val capacity: Int,
        val startAt: Instant,
        val closeAt: Instant,
        @field:NotEmpty val tiers: List<TierRequest>,
    )

    data class CreateDealResponse(val dealId: Long)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("X-Seller-Id") sellerId: Long,
        @Valid @RequestBody request: CreateDealRequest,
    ): CreateDealResponse {
        val dealId = dealCommandService.create(
            CreateDealCommand(
                productId = request.productId,
                sellerId = sellerId,
                title = request.title,
                listPrice = request.listPrice,
                minParticipants = request.minParticipants,
                capacity = request.capacity,
                startAt = request.startAt,
                closeAt = request.closeAt,
                tiers = request.tiers.map { DiscountPolicy.TierRule(it.minCount, it.discountRate) },
            ),
        )
        return CreateDealResponse(dealId)
    }

    @PostMapping("/{dealId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @RequestHeader("X-Seller-Id") sellerId: Long,
        @PathVariable dealId: Long,
    ) {
        dealCommandService.cancel(dealId, sellerId)
    }
}
