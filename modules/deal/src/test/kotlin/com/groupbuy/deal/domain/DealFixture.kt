package com.groupbuy.deal.domain

import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

/**
 * 테스트용 딜 생성. 설계서 2.2 예시와 동일한 구성:
 *   정가 100,000 / 최소 10 / 정원 200 / 티어 10→10%, 30→20%, 50→30%
 */
object DealFixture {

    val START: Instant = Instant.parse("2026-09-20T01:00:00Z")
    val CLOSE: Instant = Instant.parse("2026-09-22T13:00:00Z")

    val DEFAULT_TIERS = listOf(
        DiscountPolicy.TierRule(minCount = 10, discountRate = 10),
        DiscountPolicy.TierRule(minCount = 30, discountRate = 20),
        DiscountPolicy.TierRule(minCount = 50, discountRate = 30),
    )

    fun deal(
        id: Long? = 1L,
        listPrice: Int = 100_000,
        minParticipants: Int = 10,
        capacity: Int = 200,
        startAt: Instant = START,
        closeAt: Instant = CLOSE,
        tiers: List<DiscountPolicy.TierRule> = DEFAULT_TIERS,
    ): Deal {
        val deal = Deal.create(
            productId = 1L,
            sellerId = 1L,
            title = "무선 이어폰 공동구매",
            listPrice = listPrice,
            minParticipants = minParticipants,
            capacity = capacity,
            startAt = startAt,
            closeAt = closeAt,
            tiers = tiers,
        )
        // 영속화 없이 id 가 필요한 이벤트 발행을 테스트하기 위해 주입한다
        if (id != null) ReflectionTestUtils.setField(deal, "id", id)
        return deal
    }

    fun openDeal(): Deal = deal().apply {
        schedule()
        open(START)
    }

    fun closingDeal(): Deal = openDeal().apply {
        beginClosing(CLOSE)
    }
}
