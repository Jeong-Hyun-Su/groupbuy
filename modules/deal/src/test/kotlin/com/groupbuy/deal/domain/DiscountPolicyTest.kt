package com.groupbuy.deal.domain

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DiscountPolicyTest {

    private val policy = DiscountPolicy(DealFixture.DEFAULT_TIERS)

    @Nested
    inner class `할인율 계산` {

        @ParameterizedTest(name = "{0}명 → {1}%")
        @CsvSource(
            "0, 0",
            "9, 0",
            "10, 10",
            "29, 10",
            "30, 20",
            "49, 20",
            "50, 30",
            "200, 30",
        )
        fun `티어 경계에서 정확한 할인율을 돌려준다`(count: Int, expectedRate: Int) {
            assertThat(policy.rateFor(count)).isEqualTo(expectedRate)
        }

        @Test
        fun `다음 티어를 알려주고 최고 티어에서는 null 이다`() {
            assertThat(policy.nextTierAfter(0)).isEqualTo(DiscountPolicy.TierRule(10, 10))
            assertThat(policy.nextTierAfter(29)).isEqualTo(DiscountPolicy.TierRule(30, 20))
            assertThat(policy.nextTierAfter(30)).isEqualTo(DiscountPolicy.TierRule(50, 30))
            assertThat(policy.nextTierAfter(50)).isNull()
        }

        @Test
        fun `입력 순서와 무관하게 minCount 오름차순으로 정렬한다`() {
            val shuffled = DiscountPolicy(DealFixture.DEFAULT_TIERS.reversed())
            assertThat(shuffled.rules.map { it.minCount }).containsExactly(10, 30, 50)
        }
    }

    @Nested
    inner class `티어 검증` {

        @Test
        fun `티어가 비어 있으면 실패한다`() {
            assertInvalid(emptyList())
        }

        @Test
        fun `minCount 가 중복되면 실패한다`() {
            assertInvalid(listOf(rule(10, 10), rule(10, 20)))
        }

        @Test
        fun `인원이 많아지는데 할인율이 줄면 실패한다`() {
            assertInvalid(listOf(rule(10, 20), rule(30, 10)))
        }

        @Test
        fun `할인율이 같은 티어가 연속되면 실패한다`() {
            assertInvalid(listOf(rule(10, 10), rule(30, 10)))
        }

        @Test
        fun `할인율 범위는 0~100 이다`() {
            assertInvalid(listOf(rule(10, 101)))
            assertInvalid(listOf(rule(10, -1)))
        }

        @Test
        fun `minCount 는 1 이상이다`() {
            assertInvalid(listOf(rule(0, 10)))
        }

        private fun rule(minCount: Int, rate: Int) = DiscountPolicy.TierRule(minCount, rate)

        private fun assertInvalid(rules: List<DiscountPolicy.TierRule>) {
            assertThatThrownBy { DiscountPolicy(rules) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEAL_INVALID_TIERS)
        }
    }
}
