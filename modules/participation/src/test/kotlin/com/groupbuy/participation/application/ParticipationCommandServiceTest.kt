package com.groupbuy.participation.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.common.time.FixedTimeProvider
import com.groupbuy.deal.domain.Deal
import com.groupbuy.deal.domain.DiscountPolicy
import com.groupbuy.participation.domain.OrderStatus
import com.groupbuy.participation.domain.ParticipationStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ParticipationCommandServiceTest {

    private val start: Instant = Instant.parse("2026-09-20T01:00:00Z")
    private val close: Instant = Instant.parse("2026-09-22T13:00:00Z")
    private val ttl: Duration = Duration.ofMinutes(10)

    private lateinit var deals: InMemoryDealRepository
    private lateinit var participations: InMemoryParticipationRepository
    private lateinit var orders: InMemoryOrderRepository
    private lateinit var time: FixedTimeProvider
    private lateinit var service: ParticipationCommandService

    @BeforeEach
    fun setUp() {
        deals = InMemoryDealRepository()
        participations = InMemoryParticipationRepository()
        orders = InMemoryOrderRepository()
        time = FixedTimeProvider(start.plusSeconds(3600))
        service = ParticipationCommandService(deals, participations, orders, time, ParticipationProperties(reservationTtl = ttl))
    }

    private fun openDeal(capacity: Int = 200, listPrice: Int = 100_000): Deal {
        val deal = Deal.create(
            productId = 1, sellerId = 1, title = "무선 이어폰 공동구매",
            listPrice = listPrice, minParticipants = 1, capacity = capacity,
            startAt = start, closeAt = close,
            tiers = listOf(DiscountPolicy.TierRule(10, 10), DiscountPolicy.TierRule(30, 20)),
        )
        deals.save(deal)
        deal.schedule()
        deal.open(start)
        return deal
    }

    @Test
    fun `선점에 성공하면 정가 주문과 RESERVED 참여가 생기고 결제 파라미터를 돌려준다`() {
        val deal = openDeal()

        val result = service.participate(dealId = deal.id!!, userId = 42)

        assertThat(result.amount).isEqualTo(100_000)
        assertThat(result.orderName).isEqualTo("무선 이어폰 공동구매")
        assertThat(result.currentCount).isEqualTo(1)
        assertThat(result.reservationExpiresAt).isEqualTo(time.now().plus(ttl))

        val order = orders.findByOrderNo(result.orderNo)!!
        assertThat(order.status).isEqualTo(OrderStatus.READY)
        assertThat(order.userId).isEqualTo(42)
        assertThat(order.dealId).isEqualTo(deal.id)

        val participation = participations.findById(result.participationId)!!
        assertThat(participation.status).isEqualTo(ParticipationStatus.RESERVED)
        assertThat(participation.orderId).isEqualTo(order.id)
        assertThat(deals.lockedIds).containsExactly(deal.id)   // 딜 행 잠금 아래에서 처리했다
    }

    @Test
    fun `없는 딜이면 404`() {
        assertThatThrownBy { service.participate(dealId = 999, userId = 1) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `OPEN 이 아닌 딜은 DEAL_NOT_OPEN`() {
        val deal = Deal.create(1, 1, "t", 100_000, 1, 10, start, close, listOf(DiscountPolicy.TierRule(1, 10)))
        deals.save(deal)
        deal.schedule()

        assertError(deal.id!!, 1, ErrorCode.DEAL_NOT_OPEN)
    }

    @Test
    fun `마감 시각이 지난 딜은 아직 OPEN 이어도 DEAL_NOT_OPEN`() {
        val deal = openDeal()
        time.set(close)

        assertError(deal.id!!, 1, ErrorCode.DEAL_NOT_OPEN)
    }

    @Test
    fun `같은 사용자가 두 번 참여하면 DUPLICATE_PARTICIPATION (R2)`() {
        val deal = openDeal()
        service.participate(deal.id!!, 42)

        assertError(deal.id!!, 42, ErrorCode.DUPLICATE_PARTICIPATION)
        assertThat(participations.all()).hasSize(1)
        assertThat(orders.all()).hasSize(1)
    }

    @Test
    fun `정원이 차면 DEAL_FULL 이고 주문도 만들지 않는다 (R1)`() {
        val deal = openDeal(capacity = 2)
        service.participate(deal.id!!, 1)
        service.participate(deal.id!!, 2)

        assertError(deal.id!!, 3, ErrorCode.DEAL_FULL)
        assertThat(participations.countOccupying(deal.id!!)).isEqualTo(2)
        assertThat(orders.all()).hasSize(2)
    }

    @Test
    fun `정원 검사보다 중복 검사가 먼저다`() {
        val deal = openDeal(capacity = 1)
        service.participate(deal.id!!, 1)

        assertError(deal.id!!, 1, ErrorCode.DUPLICATE_PARTICIPATION)
    }

    @Test
    fun `선점이 만료된 사용자는 같은 행을 재사용해 새 주문으로 다시 선점한다`() {
        val deal = openDeal(capacity = 1)
        val first = service.participate(deal.id!!, 42)
        val p = participations.findById(first.participationId)!!
        p.expire(time.now().plus(ttl))
        assertThat(participations.countOccupying(deal.id!!)).isZero()

        time.advanceSeconds(ttl.seconds + 5)
        val second = service.participate(deal.id!!, 42)

        assertThat(second.participationId).isEqualTo(first.participationId)
        assertThat(second.orderNo).isNotEqualTo(first.orderNo)
        assertThat(p.status).isEqualTo(ParticipationStatus.RESERVED)
        assertThat(p.reservationExpiresAt).isEqualTo(time.now().plus(ttl))
        assertThat(orders.all()).hasSize(2)
        assertThat(participations.all()).hasSize(1)
    }

    @Test
    fun `만료된 자리는 정원에 돌아와 다른 사용자가 차지할 수 있다`() {
        val deal = openDeal(capacity = 1)
        val first = service.participate(deal.id!!, 1)
        participations.findById(first.participationId)!!.expire(time.now().plus(ttl))

        val result = service.participate(deal.id!!, 2)

        assertThat(result.currentCount).isEqualTo(1)
        assertThat(participations.countOccupying(deal.id!!)).isEqualTo(1)
    }

    private fun assertError(dealId: Long, userId: Long, expected: ErrorCode) {
        assertThatThrownBy { service.participate(dealId, userId) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(expected)
    }
}
