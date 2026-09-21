package com.groupbuy.worker.closing

import com.groupbuy.common.time.TimeProvider
import com.groupbuy.deal.application.CreateDealCommand
import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.deal.domain.DealStatus
import com.groupbuy.deal.domain.DiscountPolicy
import com.groupbuy.participation.application.ParticipationCommandService
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.OrderStatus
import com.groupbuy.participation.domain.ParticipationRepository
import com.groupbuy.participation.domain.ParticipationStatus
import com.groupbuy.payment.application.ConfirmPaymentCommand
import com.groupbuy.payment.application.PaymentConfirmService
import com.groupbuy.payment.domain.PaymentGateway
import com.groupbuy.payment.domain.PaymentRepository
import com.groupbuy.payment.domain.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 딜 생성 → 참여 → 결제 → 마감 → 환불 전 구간 (실제 PostgreSQL, Fake PG).
 * Phase 1 완료 기준의 "수동 시나리오"를 자동화한 것이다.
 *
 * Docker 가 필요하다 — 없는 환경에서는 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["spring.task.scheduling.enabled=false"])   // 스케줄러가 끼어들지 않게
class DealClosingFlowTest {

    @TestConfiguration
    class FakeGatewayConfig {
        @Bean
        @Primary
        fun fakeGateway(): PaymentGateway = CountingFakeGateway()
    }

    /** 취소 호출 횟수를 세는 Fake — 이중 환불이 없는지 본다 */
    class CountingFakeGateway : PaymentGateway {
        val cancelCalls = AtomicInteger()
        override fun providerName() = "FAKE"
        override fun approve(request: PaymentGateway.ApproveRequest) =
            PaymentGateway.ApproveResult(request.paymentKey, request.amount, "2026-09-20T11:00:00+09:00")
        override fun cancel(request: PaymentGateway.CancelRequest): PaymentGateway.CancelResult {
            cancelCalls.incrementAndGet()
            return PaymentGateway.CancelResult("cancel-${request.idempotencyKey}", request.cancelAmount)
        }
        override fun findByOrderNo(orderNo: String): PaymentGateway.ApproveResult? = null
    }

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dealCommandService: DealCommandService
    @Autowired lateinit var participationCommandService: ParticipationCommandService
    @Autowired lateinit var paymentConfirmService: PaymentConfirmService
    @Autowired lateinit var orchestrator: DealClosingOrchestrator
    @Autowired lateinit var dealRepository: DealRepository
    @Autowired lateinit var participationRepository: ParticipationRepository
    @Autowired lateinit var orderRepository: OrderRepository
    @Autowired lateinit var paymentRepository: PaymentRepository
    @Autowired lateinit var gateway: PaymentGateway
    @Autowired lateinit var timeProvider: TimeProvider

    private fun newUser(): Long = jdbc.sql("insert into users (email, name) values (:email, 'tester') returning id")
        .param("email", "${UUID.randomUUID()}@test.local").query(Long::class.javaObjectType).single()

    /** 마감 시각이 이미 지난 딜을 만든다 — 마감 판정을 바로 돌리기 위해 close_at 을 직접 과거로 밀어 넣는다 */
    private fun openDealClosingNow(minParticipants: Int, capacity: Int = 100): Long {
        val sellerId = jdbc.sql("insert into sellers (name) values ('seller') returning id").query(Long::class.javaObjectType).single()
        val productId = jdbc.sql("insert into products (seller_id, name, list_price) values (:s, 'earbuds', 100000) returning id")
            .param("s", sellerId).query(Long::class.javaObjectType).single()
        val now = timeProvider.now()
        val dealId = dealCommandService.create(
            CreateDealCommand(
                productId = productId, sellerId = sellerId, title = "마감 통합 테스트 딜",
                listPrice = 100_000, minParticipants = minParticipants, capacity = capacity,
                startAt = now.minus(Duration.ofHours(2)), closeAt = now.plus(Duration.ofHours(1)),
                tiers = listOf(
                    DiscountPolicy.TierRule(2, 10),
                    DiscountPolicy.TierRule(3, 20),
                ),
            ),
        )
        dealCommandService.openDueDeals()
        return dealId
    }

    private fun pushCloseAtToPast(dealId: Long) {
        jdbc.sql("update deals set close_at = now() - interval '1 minute' where id = :id").param("id", dealId).update()
    }

    private fun participateAndPay(dealId: Long): Long {
        val userId = newUser()
        val reserved = participationCommandService.participate(dealId, userId)
        paymentConfirmService.confirm(
            ConfirmPaymentCommand("pay_${UUID.randomUUID()}", reserved.orderNo, reserved.amount),
        )
        return reserved.participationId
    }

    @Test
    fun `성사 딜은 확정 할인율로 전원 차액이 환불되고 SETTLED 가 된다`() {
        val dealId = openDealClosingNow(minParticipants = 2)
        val participationIds = (1..3).map { participateAndPay(dealId) }
        pushCloseAtToPast(dealId)

        val report = orchestrator.close(dealId)

        // 3명 → 20% 티어. 전원 80,000원 확정, 20,000원 환불 (R3, R4)
        assertThat(report.closed).isTrue()
        assertThat(report.result!!.finalParticipantCount).isEqualTo(3)
        assertThat(report.result!!.finalDiscountRate).isEqualTo(20)
        assertThat(report.refundedCount).isEqualTo(3)
        assertThat(report.failedCount).isZero()

        assertThat(dealRepository.findById(dealId)!!.status).isEqualTo(DealStatus.SETTLED)

        participationIds.forEach { id ->
            val participation = participationRepository.findById(id)!!
            assertThat(participation.status).isEqualTo(ParticipationStatus.FINALIZED)

            val order = orderRepository.findById(participation.orderId)!!
            assertThat(order.status).isEqualTo(OrderStatus.FINALIZED)
            assertThat(order.finalAmount).isEqualTo(80_000)
            assertThat(order.tierRefundAmount()).isEqualTo(20_000)
        }

        // 환불 합계 = 3 × 20,000
        val refunded = jdbc.sql(
            """
            select coalesce(sum(r.amount), 0) from refunds r
              join payments p on p.id = r.payment_id
              join orders o on o.id = p.order_id
             where o.deal_id = :dealId and r.status = 'COMPLETED'
            """,
        ).param("dealId", dealId).query(Long::class.javaObjectType).single()
        assertThat(refunded).isEqualTo(60_000)
    }

    @Test
    fun `무산 딜은 전원 전액 환불된다 (R5)`() {
        val dealId = openDealClosingNow(minParticipants = 5)
        val participationIds = (1..2).map { participateAndPay(dealId) }
        pushCloseAtToPast(dealId)

        val report = orchestrator.close(dealId)

        assertThat(report.result!!.status).isEqualTo(DealStatus.FAILED)
        assertThat(report.refundedCount).isEqualTo(2)
        assertThat(dealRepository.findById(dealId)!!.status).isEqualTo(DealStatus.FAILED)

        participationIds.forEach { id ->
            val participation = participationRepository.findById(id)!!
            assertThat(participation.status).isEqualTo(ParticipationStatus.REFUNDED)

            val order = orderRepository.findById(participation.orderId)!!
            assertThat(order.status).isEqualTo(OrderStatus.CANCELED)

            val payment = paymentRepository.findAllByOrderIds(listOf(order.id!!)).single()
            assertThat(payment.status).isEqualTo(PaymentStatus.CANCELED)
        }

        val refunded = jdbc.sql(
            """
            select coalesce(sum(r.amount), 0) from refunds r
              join payments p on p.id = r.payment_id
              join orders o on o.id = p.order_id
             where o.deal_id = :dealId and r.status = 'COMPLETED'
            """,
        ).param("dealId", dealId).query(Long::class.javaObjectType).single()
        assertThat(refunded).isEqualTo(200_000)     // 2 × 정가
    }

    @Test
    fun `결제하지 않은 선점은 인원에서 빠진다 (ADR-07)`() {
        val dealId = openDealClosingNow(minParticipants = 1)
        participateAndPay(dealId)                                        // 결제 완료
        participationCommandService.participate(dealId, newUser())       // 선점만 하고 결제 안 함
        pushCloseAtToPast(dealId)

        val report = orchestrator.close(dealId)

        // 확정 1명만 센다 — 10% 티어(2명)에 못 미쳐 할인율 0%
        assertThat(report.result!!.finalParticipantCount).isEqualTo(1)
        assertThat(report.result!!.finalDiscountRate).isZero()
        assertThat(report.refundedCount).isEqualTo(1)
    }

    @Test
    fun `마감을 두 번 시도해도 환불은 한 번만 일어난다 (R8, R6)`() {
        val dealId = openDealClosingNow(minParticipants = 1)
        repeat(2) { participateAndPay(dealId) }
        pushCloseAtToPast(dealId)
        val before = (gateway as CountingFakeGateway).cancelCalls.get()

        val first = orchestrator.close(dealId)
        val second = orchestrator.close(dealId)     // 다른 인스턴스가 중복 시도한 상황

        assertThat(first.closed).isTrue()
        assertThat(second.closed).isFalse()          // CLOSING 점유 실패
        assertThat((gateway as CountingFakeGateway).cancelCalls.get() - before).isEqualTo(2)

        val refundRows = jdbc.sql(
            """
            select count(*) from refunds r
              join payments p on p.id = r.payment_id
              join orders o on o.id = p.order_id
             where o.deal_id = :dealId
            """,
        ).param("dealId", dealId).query(Long::class.javaObjectType).single()
        assertThat(refundRows).isEqualTo(2)          // 참여자당 1건. 이중 환불 없음
    }
}
