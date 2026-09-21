package com.groupbuy.deal.application

import com.groupbuy.common.time.FixedTimeProvider
import com.groupbuy.deal.domain.DealFixture
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.deal.domain.DealStatus
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 마감 점유 규칙. 판정·환불이 롤백돼 CLOSING 에 남은 딜이 다시 집히는지가 핵심이다 */
class DealCommandServiceTest {

    private val dealRepository = mockk<DealRepository>()
    private val service = DealCommandService(dealRepository, FixedTimeProvider(DealFixture.CLOSE))

    @Test
    fun `OPEN 딜은 점유에 성공하고 CLOSING 이 된다`() {
        val deal = DealFixture.openDeal()
        every { dealRepository.findByIdForUpdate(1L) } returns deal

        assertThat(service.beginClosing(1L)).isTrue()
        assertThat(deal.status).isEqualTo(DealStatus.CLOSING)
    }

    @Test
    fun `CLOSING 에 남은 딜은 재개할 수 있다 — 환불 롤백 후 다음 폴링이 이어받는다`() {
        every { dealRepository.findByIdForUpdate(1L) } returns DealFixture.closingDeal()

        assertThat(service.beginClosing(1L)).isTrue()
    }

    @Test
    fun `판정이 끝난 딜은 점유할 수 없다 (R8)`() {
        val closed = DealFixture.closingDeal().apply { finishClosing(0, DealFixture.CLOSE) }
        every { dealRepository.findByIdForUpdate(1L) } returns closed

        assertThat(service.beginClosing(1L)).isFalse()
    }
}
