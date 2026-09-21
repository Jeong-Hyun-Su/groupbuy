package com.groupbuy.api

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.time.TimeProvider
import com.groupbuy.deal.application.CreateDealCommand
import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.DiscountPolicy
import com.groupbuy.participation.application.ParticipationCommandService
import com.groupbuy.participation.domain.ParticipationRepository
import com.groupbuy.participation.domain.ParticipationStatus
import com.groupbuy.payment.application.ConfirmPaymentCommand
import com.groupbuy.payment.application.PaymentConfirmService
import com.groupbuy.payment.domain.PaymentGateway
import com.groupbuy.payment.domain.PaymentRepository
import com.groupbuy.payment.domain.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 참여 → 결제 승인까지의 통합 흐름 (실제 PostgreSQL, Fake PG).
 * confirm 과 webhook 이 동시에 도착해도 승인이 한 번만 일어나는지(R6)가 핵심이다.
 *
 * Docker 가 필요하다 — 없는 환경에서는 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PaymentFlowTest {

    /** 실제 토스를 부르지 않도록 게이트웨이를 갈아끼운다 */
    @TestConfiguration
    class FakeGatewayConfig {
        @Bean
        @Primary
        fun fakeGateway(): PaymentGateway = object : PaymentGateway {
            val calls = AtomicInteger()
            override fun providerName() = "FAKE"
            override fun approve(request: PaymentGateway.ApproveRequest): PaymentGateway.ApproveResult {
                calls.incrementAndGet()
                return PaymentGateway.ApproveResult(request.paymentKey, request.amount, "2026-09-20T11:00:00+09:00")
            }
            override fun cancel(request: PaymentGateway.CancelRequest) =
                PaymentGateway.CancelResult("cancel-${request.idempotencyKey}", request.cancelAmount)
            override fun findByOrderNo(orderNo: String): PaymentGateway.ApproveResult? = null
        }
    }

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dealCommandService: DealCommandService
    @Autowired lateinit var participationCommandService: ParticipationCommandService
    @Autowired lateinit var participationRepository: ParticipationRepository
    @Autowired lateinit var paymentConfirmService: PaymentConfirmService
    @Autowired lateinit var paymentRepository: PaymentRepository
    @Autowired lateinit var timeProvider: TimeProvider

    private fun newUser(): Long = jdbc.sql("insert into users (email, name) values (:email, 'tester') returning id")
        .param("email", "${UUID.randomUUID()}@test.local").query(Long::class.javaObjectType).single()

    private fun openDeal(capacity: Int = 10): Long {
        val sellerId = jdbc.sql("insert into sellers (name) values ('seller') returning id").query(Long::class.javaObjectType).single()
        val productId = jdbc.sql("insert into products (seller_id, name, list_price) values (:s, 'earbuds', 100000) returning id")
            .param("s", sellerId).query(Long::class.javaObjectType).single()
        val now = timeProvider.now()
        val dealId = dealCommandService.create(
            CreateDealCommand(
                productId = productId, sellerId = sellerId, title = "결제 통합 테스트 딜",
                listPrice = 100_000, minParticipants = 1, capacity = capacity,
                startAt = now.minus(Duration.ofMinutes(1)), closeAt = now.plus(Duration.ofDays(1)),
                tiers = listOf(DiscountPolicy.TierRule(10, 10)),
            ),
        )
        dealCommandService.openDueDeals()
        return dealId
    }

    @Test
    fun `참여 후 승인하면 결제와 참여가 함께 확정된다`() {
        val dealId = openDeal()
        val userId = newUser()
        val reserved = participationCommandService.participate(dealId, userId)

        val result = paymentConfirmService.confirm(
            ConfirmPaymentCommand(paymentKey = "pay_${UUID.randomUUID()}", orderNo = reserved.orderNo, amount = reserved.amount),
        )

        assertThat(result.newlyApproved).isTrue()
        assertThat(paymentRepository.findByOrderNo(reserved.orderNo)!!.status).isEqualTo(PaymentStatus.APPROVED)
        assertThat(participationRepository.findById(reserved.participationId)!!.status).isEqualTo(ParticipationStatus.CONFIRMED)
    }

    @Test
    fun `금액을 조작한 승인 요청은 거부된다 (R4)`() {
        val dealId = openDeal()
        val userId = newUser()
        val reserved = participationCommandService.participate(dealId, userId)

        assertThatThrownBy {
            paymentConfirmService.confirm(ConfirmPaymentCommand("pay_x", reserved.orderNo, amount = 1_000))
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH)

        assertThat(paymentRepository.findByOrderNo(reserved.orderNo)).isNull()
        assertThat(participationRepository.findById(reserved.participationId)!!.status).isEqualTo(ParticipationStatus.RESERVED)
    }

    @Test
    fun `confirm 과 webhook 이 동시에 도착해도 승인은 한 번만 일어난다 (R6)`() {
        val dealId = openDeal()
        val userId = newUser()
        val reserved = participationCommandService.participate(dealId, userId)
        val paymentKey = "pay_${UUID.randomUUID()}"
        val command = ConfirmPaymentCommand(paymentKey, reserved.orderNo, reserved.amount)

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val newlyApproved = AtomicInteger()
        val idempotent = AtomicInteger()
        val errors = AtomicInteger()
        try {
            repeat(threads) {
                pool.submit {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    try {
                        if (paymentConfirmService.confirm(command).newlyApproved) newlyApproved.incrementAndGet()
                        else idempotent.incrementAndGet()
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    }
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            pool.shutdown()
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()
        } finally {
            pool.shutdownNow()
        }

        // 정확히 한 번만 승인되고, 나머지는 멱등 응답이거나 잠금 경쟁으로 실패했더라도 이중 승인은 없다
        assertThat(newlyApproved.get()).isEqualTo(1)
        assertThat(newlyApproved.get() + idempotent.get() + errors.get()).isEqualTo(threads)

        val payment = paymentRepository.findByOrderNo(reserved.orderNo)!!
        assertThat(payment.status).isEqualTo(PaymentStatus.APPROVED)
        assertThat(payment.pgPaymentKey).isEqualTo(paymentKey)

        val approvedRows = jdbc.sql("select count(*) from payments where order_no = :o and status = 'APPROVED'")
            .param("o", reserved.orderNo).query(Long::class.javaObjectType).single()
        assertThat(approvedRows).isEqualTo(1)
    }
}
