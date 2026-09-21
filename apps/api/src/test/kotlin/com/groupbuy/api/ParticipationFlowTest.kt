package com.groupbuy.api

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.time.TimeProvider
import com.groupbuy.deal.application.CreateDealCommand
import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.DiscountPolicy
import com.groupbuy.participation.application.ParticipationCommandService
import com.groupbuy.participation.domain.ParticipationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
 * Phase 1 참여 흐름 통합 테스트 (실제 PostgreSQL). R1·R2 가 DB `FOR UPDATE` 직렬화로 지켜지는지 본다.
 * Docker 가 필요하다 — 없는 환경에서는 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ParticipationFlowTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dealCommandService: DealCommandService
    @Autowired lateinit var participationCommandService: ParticipationCommandService
    @Autowired lateinit var participationRepository: ParticipationRepository
    @Autowired lateinit var timeProvider: TimeProvider

    private fun newUser(): Long = jdbc.sql("insert into users (email, name) values (:email, 'tester') returning id")
        .param("email", "${UUID.randomUUID()}@test.local").query(Long::class.javaObjectType).single()

    private fun newProduct(): Pair<Long, Long> {
        val sellerId = jdbc.sql("insert into sellers (name) values ('seller') returning id").query(Long::class.javaObjectType).single()
        val productId = jdbc.sql("insert into products (seller_id, name, list_price) values (:s, 'earbuds', 100000) returning id")
            .param("s", sellerId).query(Long::class.javaObjectType).single()
        return sellerId to productId
    }

    /** 시작 시각을 과거로 두고 스케줄러 유스케이스를 직접 호출해 OPEN 으로 만든다 */
    private fun openDeal(capacity: Int): Long {
        val (sellerId, productId) = newProduct()
        val now = timeProvider.now()
        val dealId = dealCommandService.create(
            CreateDealCommand(
                productId = productId, sellerId = sellerId, title = "통합 테스트 딜",
                listPrice = 100_000, minParticipants = 1, capacity = capacity,
                startAt = now.minus(Duration.ofMinutes(1)), closeAt = now.plus(Duration.ofDays(1)),
                tiers = listOf(DiscountPolicy.TierRule(10, 10), DiscountPolicy.TierRule(30, 20)),
            ),
        )
        assertThat(dealCommandService.openDueDeals()).isGreaterThanOrEqualTo(1)
        return dealId
    }

    @Test
    fun `선점 → 중복 거부 → 정원 초과 거부`() {
        val dealId = openDeal(capacity = 1)
        val alice = newUser()
        val bob = newUser()

        val result = participationCommandService.participate(dealId, alice)
        assertThat(result.amount).isEqualTo(100_000)
        assertThat(result.currentCount).isEqualTo(1)

        assertThatThrownBy { participationCommandService.participate(dealId, alice) }
            .isInstanceOf(DomainException::class.java).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_PARTICIPATION)
        assertThatThrownBy { participationCommandService.participate(dealId, bob) }
            .isInstanceOf(DomainException::class.java).extracting("errorCode").isEqualTo(ErrorCode.DEAL_FULL)

        assertThat(participationRepository.countOccupying(dealId)).isEqualTo(1)
    }

    @Test
    fun `동시에 50명이 정원 10 딜에 참여하면 정확히 10명만 성공한다 (R1)`() {
        val capacity = 10
        val attempts = 50
        val dealId = openDeal(capacity)
        val users = (1..attempts).map { newUser() }

        val pool = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val go = CountDownLatch(1)
        val success = AtomicInteger()
        val full = AtomicInteger()
        val other = AtomicInteger()
        try {
            users.forEach { userId ->
                pool.submit {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    try {
                        participationCommandService.participate(dealId, userId)
                        success.incrementAndGet()
                    } catch (e: DomainException) {
                        if (e.errorCode == ErrorCode.DEAL_FULL) full.incrementAndGet() else other.incrementAndGet()
                    } catch (e: Exception) {
                        other.incrementAndGet()
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

        assertThat(success.get()).isEqualTo(capacity)
        assertThat(full.get()).isEqualTo(attempts - capacity)
        assertThat(other.get()).isZero()
        assertThat(participationRepository.countOccupying(dealId)).isEqualTo(capacity)
    }
}
