package com.groupbuy.participation.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.time.FixedTimeProvider
import com.groupbuy.deal.domain.Deal
import com.groupbuy.deal.domain.DiscountPolicy
import com.groupbuy.participation.domain.ParticipationStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** 승인된 결제를 참여에 반영하는 규칙. 핵심은 "확정 불가를 예외가 아니라 REJECTED 로 알린다" 이다 */
class ParticipationConfirmServiceTest {

    private val start: Instant = Instant.parse("2026-09-20T01:00:00Z")
    /** 선점(start+1h, TTL 10분)이 살아 있는 동안 마감이 오도록 잡는다 — 마감 분기만 따로 보기 위해 */
    private val close: Instant = start.plusSeconds(3600 + 300)
    private val ttl: Duration = Duration.ofMinutes(10)

    private val deals = InMemoryDealRepository()
    private val participations = InMemoryParticipationRepository()
    private val orders = InMemoryOrderRepository()
    private val time = FixedTimeProvider(start.plusSeconds(3600))
    private val service = ParticipationConfirmService(participations, orders, deals, time)

    private lateinit var deal: Deal
    private var orderId: Long = 0
    private var participationId: Long = 0

    @BeforeEach
    fun reserve() {
        deal = Deal.create(
            productId = 1, sellerId = 1, title = "딜", listPrice = 100_000, minParticipants = 1, capacity = 10,
            startAt = start, closeAt = close, tiers = listOf(DiscountPolicy.TierRule(1, 10)),
        )
        deals.save(deal)
        deal.schedule()
        deal.open(start)

        val reserved = ParticipationCommandService(deals, participations, orders, time, ParticipationProperties(ttl))
            .participate(deal.id!!, userId = 42)
        participationId = reserved.participationId
        orderId = orders.findByOrderNo(reserved.orderNo)!!.id!!
    }

    private fun status() = participations.findById(participationId)!!.status

    @Test
    fun `선점 중이면 확정된다`() {
        service.ensureConfirmable(orderId)

        assertThat(service.confirmByOrder(orderId)).isEqualTo(ConfirmOutcome.CONFIRMED)
        assertThat(status()).isEqualTo(ParticipationStatus.CONFIRMED)
        assertThat(service.confirmByOrder(orderId)).isEqualTo(ConfirmOutcome.ALREADY_CONFIRMED)
    }

    @Test
    fun `선점이 만료됐으면 예외 없이 REJECTED 다 — 승인 기록을 롤백시키면 안 된다`() {
        time.advanceSeconds(ttl.seconds)     // 마감도 지났지만 딜은 아직 OPEN — 만료만으로 거부돼야 한다

        assertThatThrownBy { service.ensureConfirmable(orderId) }.isInstanceOf(DomainException::class.java)
        assertThat(service.confirmByOrder(orderId)).isEqualTo(ConfirmOutcome.REJECTED)
        assertThat(status()).isEqualTo(ParticipationStatus.RESERVED)
    }

    @Test
    fun `마감 점유 뒤에 도착한 승인은 REJECTED 다 (ADR-07)`() {
        time.set(close)
        deal.beginClosing(close)
        assertThat(service.confirmByOrder(orderId)).isEqualTo(ConfirmOutcome.REJECTED)
        assertThat(status()).isEqualTo(ParticipationStatus.RESERVED)
    }
}
