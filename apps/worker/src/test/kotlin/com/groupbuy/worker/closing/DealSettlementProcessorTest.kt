package com.groupbuy.worker.closing

import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.CloseResult
import com.groupbuy.deal.domain.DealStatus
import com.groupbuy.participation.application.ConfirmedParticipant
import com.groupbuy.participation.application.ParticipationClosingService
import com.groupbuy.payment.application.RefundCommand
import com.groupbuy.payment.application.RefundOutcome
import com.groupbuy.payment.application.RefundService
import com.groupbuy.payment.domain.RefundReason
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 마감 판정 + 환불. 트랜잭션 경계는 통합 테스트에서 검증한다 */
class DealSettlementProcessorTest {

    private val dealId = 1L
    private val listPrice = 100_000

    private lateinit var dealCommandService: DealCommandService
    private lateinit var closingService: ParticipationClosingService
    private lateinit var refundService: RefundService
    private lateinit var processor: DealSettlementProcessor

    @BeforeEach
    fun setUp() {
        dealCommandService = mockk(relaxed = true)
        closingService = mockk(relaxed = true)
        refundService = mockk()
        processor = DealSettlementProcessor(dealCommandService, closingService, refundService)

        every { refundService.refund(any()) } answers {
            val cmd = firstArg<RefundCommand>()
            RefundOutcome(cmd.orderId, refundId = 1, amount = cmd.amount, succeeded = true)
        }
    }

    private fun participants(count: Int) = (1..count).map {
        ConfirmedParticipant(participationId = it.toLong(), userId = it.toLong(), orderId = 100L + it, listAmount = listPrice)
    }

    private fun closeResult(count: Int, rate: Int, succeeded: Boolean = true) = CloseResult(
        dealId = dealId,
        status = if (succeeded) DealStatus.SUCCEEDED else DealStatus.FAILED,
        finalParticipantCount = count,
        finalDiscountRate = rate,
        finalPrice = if (succeeded) listPrice * (100 - rate) / 100 else listPrice,
    )

    @Test
    fun `성사되면 전원에게 같은 차액이 환불되고 딜은 정산 대상이 된다`() {
        every { closingService.confirmedParticipants(dealId) } returns participants(3)
        every { dealCommandService.finishClosing(dealId, 3) } returns closeResult(3, rate = 20)
        every { closingService.markAdjusting(any(), 80_000) } returns 20_000

        val report = processor.settle(dealId)

        assertThat(report.closed).isTrue()
        assertThat(report.refundedCount).isEqualTo(3)
        assertThat(report.failedCount).isZero()

        // 확정 금액은 전원 동일 (R3), 차액도 동일 (R4)
        val commands = mutableListOf<RefundCommand>()
        verify(exactly = 3) { refundService.refund(capture(commands)) }
        assertThat(commands).allMatch { it.amount == 20_000 && it.reason == RefundReason.TIER_ADJUST }
        assertThat(commands.map { it.orderId }).containsExactlyInAnyOrder(101, 102, 103)

        verify(exactly = 1) { dealCommandService.markSettled(dealId) }
    }

    @Test
    fun `무산되면 전원에게 정가가 전액 환불된다 (R5)`() {
        every { closingService.confirmedParticipants(dealId) } returns participants(2)
        every { dealCommandService.finishClosing(dealId, 2) } returns closeResult(2, rate = 0, succeeded = false)
        every { closingService.markRefunding(any()) } returns listPrice

        val report = processor.settle(dealId)

        assertThat(report.refundedCount).isEqualTo(2)

        val commands = mutableListOf<RefundCommand>()
        verify(exactly = 2) { refundService.refund(capture(commands)) }
        assertThat(commands).allMatch { it.amount == listPrice && it.reason == RefundReason.DEAL_FAILED }

        // 무산 딜은 정산 대상이 아니다
        verify(exactly = 0) { dealCommandService.markSettled(any()) }
    }

    @Test
    fun `확정 인원은 CONFIRMED 만 세고 그 수로 판정한다 (ADR-07)`() {
        every { closingService.confirmedParticipants(dealId) } returns participants(47)
        every { dealCommandService.finishClosing(dealId, 47) } returns closeResult(47, rate = 20)
        every { closingService.markAdjusting(any(), any()) } returns 20_000

        processor.settle(dealId)

        verify(exactly = 1) { dealCommandService.finishClosing(dealId, 47) }
    }

    @Test
    fun `참여자가 없으면 무산 판정만 하고 환불은 없다`() {
        every { closingService.confirmedParticipants(dealId) } returns emptyList()
        every { dealCommandService.finishClosing(dealId, 0) } returns closeResult(0, rate = 0, succeeded = false)

        val report = processor.settle(dealId)

        assertThat(report.closed).isTrue()
        assertThat(report.refundedCount).isZero()
        verify(exactly = 0) { refundService.refund(any()) }
    }

    @Test
    fun `차액이 0원이면 PG 를 호출하지 않는다`() {
        every { closingService.confirmedParticipants(dealId) } returns participants(1)
        every { dealCommandService.finishClosing(dealId, 1) } returns closeResult(1, rate = 0)
        every { closingService.markAdjusting(any(), listPrice) } returns 0     // 할인 0% → 차액 없음

        val report = processor.settle(dealId)

        assertThat(report.refundedCount).isEqualTo(1)
        verify(exactly = 0) { refundService.refund(any()) }
        verify(exactly = 1) { closingService.markRefundSettled(1, true) }
    }

    @Test
    fun `환불이 하나라도 실패하면 딜을 정산 대상으로 올리지 않는다`() {
        every { closingService.confirmedParticipants(dealId) } returns participants(2)
        every { dealCommandService.finishClosing(dealId, 2) } returns closeResult(2, rate = 10)
        every { closingService.markAdjusting(any(), 90_000) } returns 10_000
        every { refundService.refund(match { it.orderId == 101L }) } returns
            RefundOutcome(101, null, 10_000, succeeded = false, error = "취소 가능한 결제가 아닙니다.")
        every { refundService.refund(match { it.orderId == 102L }) } returns
            RefundOutcome(102, 1, 10_000, succeeded = true)

        val report = processor.settle(dealId)

        assertThat(report.refundedCount).isEqualTo(1)
        assertThat(report.failedCount).isEqualTo(1)
        verify(exactly = 0) { dealCommandService.markSettled(any()) }
    }

    @Test
    fun `환불 실행 중 예외는 그대로 올라가 트랜잭션을 롤백시킨다 (Phase 1 의 의도된 한계)`() {
        every { closingService.confirmedParticipants(dealId) } returns participants(1)
        every { dealCommandService.finishClosing(dealId, 1) } returns closeResult(1, rate = 20)
        every { closingService.markAdjusting(any(), any()) } returns 20_000
        every { refundService.refund(any()) } throws IllegalStateException("PG 타임아웃")

        assertThatThrownBy { processor.settle(dealId) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("PG 타임아웃")
    }
}
