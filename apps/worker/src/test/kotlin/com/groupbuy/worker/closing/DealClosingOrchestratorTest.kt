package com.groupbuy.worker.closing

import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.CloseResult
import com.groupbuy.deal.domain.DealStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 마감 점유와 딜 단위 격리. 판정·환불 로직은 DealSettlementProcessorTest 에서 본다 */
class DealClosingOrchestratorTest {

    private val dealId = 1L

    private lateinit var dealCommandService: DealCommandService
    private lateinit var settlementProcessor: DealSettlementProcessor
    private lateinit var orchestrator: DealClosingOrchestrator

    @BeforeEach
    fun setUp() {
        dealCommandService = mockk(relaxed = true)
        settlementProcessor = mockk()
        orchestrator = DealClosingOrchestrator(dealCommandService, settlementProcessor)
    }

    private fun report(dealId: Long) = ClosingReport(
        dealId = dealId,
        closed = true,
        result = CloseResult(dealId, DealStatus.FAILED, 0, 0, 100_000),
    )

    @Test
    fun `점유에 성공하면 판정을 진행한다`() {
        every { dealCommandService.beginClosing(dealId) } returns true
        every { settlementProcessor.settle(dealId) } returns report(dealId)

        val result = orchestrator.close(dealId)

        assertThat(result.closed).isTrue()
        verify(exactly = 1) { settlementProcessor.settle(dealId) }
    }

    @Test
    fun `다른 인스턴스가 이미 점유했으면 판정하지 않는다 (R8)`() {
        every { dealCommandService.beginClosing(dealId) } returns false

        val result = orchestrator.close(dealId)

        assertThat(result.closed).isFalse()
        assertThat(result.skippedReason).isNotNull()
        verify(exactly = 0) { settlementProcessor.settle(any()) }
    }

    @Test
    fun `한 딜의 마감 실패가 다른 딜을 막지 않는다`() {
        every { dealCommandService.findDueToClose(any()) } returns listOf(1L, 2L)
        every { dealCommandService.beginClosing(1L) } throws IllegalStateException("DB 오류")
        every { dealCommandService.beginClosing(2L) } returns true
        every { settlementProcessor.settle(2L) } returns report(2L)

        val reports = orchestrator.closeDueDeals()

        assertThat(reports).hasSize(2)
        assertThat(reports[0].closed).isFalse()
        assertThat(reports[0].skippedReason).contains("DB 오류")
        assertThat(reports[1].closed).isTrue()
    }

    @Test
    fun `마감 대상이 없으면 빈 결과다`() {
        every { dealCommandService.findDueToClose(any()) } returns emptyList()

        assertThat(orchestrator.closeDueDeals()).isEmpty()
        verify(exactly = 0) { settlementProcessor.settle(any()) }
    }
}
