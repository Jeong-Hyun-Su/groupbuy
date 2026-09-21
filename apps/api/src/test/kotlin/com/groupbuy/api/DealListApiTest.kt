package com.groupbuy.api

import com.groupbuy.common.time.TimeProvider
import com.groupbuy.deal.application.CreateDealCommand
import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.DiscountPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

/**
 * UC-02 딜 목록 API. Docker 가 필요하다 — 없는 환경에서는 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
class DealListApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dealCommandService: DealCommandService
    @Autowired lateinit var timeProvider: TimeProvider

    private fun createOpenDeal(title: String, closeInHours: Long): Long {
        val sellerId = jdbc.sql("insert into sellers (name) values ('seller') returning id").query(Long::class.javaObjectType).single()
        val productId = jdbc.sql("insert into products (seller_id, name, list_price) values (:s, :n, 100000) returning id")
            .param("s", sellerId).param("n", "product-${UUID.randomUUID()}").query(Long::class.javaObjectType).single()
        val now = timeProvider.now()
        val dealId = dealCommandService.create(
            CreateDealCommand(
                productId = productId, sellerId = sellerId, title = title,
                listPrice = 100_000, minParticipants = 1, capacity = 100,
                startAt = now.minus(Duration.ofMinutes(1)), closeAt = now.plus(Duration.ofHours(closeInHours)),
                tiers = listOf(DiscountPolicy.TierRule(10, 10)),
            ),
        )
        dealCommandService.openDueDeals()
        return dealId
    }

    @Test
    fun `마감 임박순으로 정렬된다`() {
        val marker = UUID.randomUUID().toString().take(8)
        createOpenDeal("정렬테스트-$marker-늦음", closeInHours = 48)
        createOpenDeal("정렬테스트-$marker-이름", closeInHours = 1)
        createOpenDeal("정렬테스트-$marker-중간", closeInHours = 24)

        mockMvc.get("/api/deals") {
            param("q", "정렬테스트-$marker")
            param("sort", "closing_soon")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(3) }
            jsonPath("$.items[0].title") { value("정렬테스트-$marker-이름") }
            jsonPath("$.items[1].title") { value("정렬테스트-$marker-중간") }
            jsonPath("$.items[2].title") { value("정렬테스트-$marker-늦음") }
        }
    }

    @Test
    fun `제목 키워드로 검색한다`() {
        val marker = UUID.randomUUID().toString().take(8)
        createOpenDeal("검색대상-$marker-이어폰", closeInHours = 2)
        createOpenDeal("검색제외-$marker-키보드", closeInHours = 2)

        mockMvc.get("/api/deals") {
            param("q", "검색대상-$marker")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.items[0].title") { value("검색대상-$marker-이어폰") }
            jsonPath("$.items[0].currentCount") { value(0) }
            jsonPath("$.items[0].projectedDiscountRate") { value(0) }
        }
    }

    @Test
    fun `지원하지 않는 정렬은 400 이다`() {
        mockMvc.get("/api/deals") {
            param("sort", "popular")      // Phase 5 (ES) 로 미룬 정렬
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `페이지 크기는 상한을 넘지 않는다`() {
        mockMvc.get("/api/deals") {
            param("size", "9999")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.size") { value(100) }
        }
    }
}
