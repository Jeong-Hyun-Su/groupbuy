package com.groupbuy.common.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MoneyTest {

    @Test
    fun `할인 금액은 원 단위로 내림한다`() {
        assertThat(Money.discounted(100_000, 20)).isEqualTo(80_000)
        assertThat(Money.discounted(99_999, 10)).isEqualTo(89_999)   // 89,999.1 → 89,999
        assertThat(Money.discounted(100_000, 0)).isEqualTo(100_000)
        assertThat(Money.discounted(100_000, 100)).isEqualTo(0)
    }

    @Test
    fun `차액 환불액은 결제액과 확정액의 차이다`() {
        assertThat(Money.tierRefund(100_000, 80_000)).isEqualTo(20_000)
        assertThatThrownBy { Money.tierRefund(80_000, 100_000) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `수수료는 만분율로 계산하고 내림한다`() {
        assertThat(Money.fee(80_000, 350)).isEqualTo(2_800)     // 3.5%
        assertThat(Money.fee(99_999, 350)).isEqualTo(3_499)     // 3,499.965 → 3,499
    }
}
