package com.groupbuy.worker.closing

import com.groupbuy.common.domain.Money
import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.CloseResult
import com.groupbuy.participation.application.ConfirmedParticipant
import com.groupbuy.participation.application.ParticipationClosingService
import com.groupbuy.payment.application.RefundCommand
import com.groupbuy.payment.application.RefundService
import com.groupbuy.payment.domain.RefundReason
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 마감 판정 + 환불. **한 트랜잭션**이다 (설계서 11 Phase 1).
 *
 * `DealClosingOrchestrator` 와 분리한 이유는 트랜잭션 경계를 프록시가 열게 하기 위함이다.
 * 같은 클래스 안에서 `@Transactional` 메서드를 호출하면 프록시를 타지 않아 트랜잭션이 안 열린다.
 *
 * 환불이 하나라도 실패하면 예외가 올라가 판정까지 통째로 롤백된다.
 * "돈이 안 맞느니 마감을 미룬다" 는 선택 — 부분 성공을 허용하면 일부만 환불된 채 SUCCEEDED 로 굳어
 * R4·R5 가 깨진다. 딜은 CLOSING 에서 되돌아가고 다음 폴링이 재시도한다.
 */
@Component
class DealSettlementProcessor(
    private val dealCommandService: DealCommandService,
    private val participationClosingService: ParticipationClosingService,
    private val refundService: RefundService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun settle(dealId: Long): ClosingReport {
        // 확정 인원 — RESERVED 제외 (ADR-07)
        val participants = participationClosingService.confirmedParticipants(dealId)

        // 판정 — 전원 같은 할인율 (R3)
        val result = dealCommandService.finishClosing(dealId, participants.size)
        log.info(
            "deal closed: dealId={} status={} count={} rate={}%",
            dealId, result.status, result.finalParticipantCount, result.finalDiscountRate,
        )

        // 환불 — 성사면 차액, 무산이면 전액
        val succeeded = participants.count { refundOne(it, result) }

        if (result.succeeded && succeeded == participants.size) {
            dealCommandService.markSettled(dealId)
        }

        return ClosingReport(
            dealId = dealId,
            closed = true,
            result = result,
            refundedCount = succeeded,
            failedCount = participants.size - succeeded,
        )
    }

    /**
     * 참여자 1명의 환불.
     * 성사면 차액(정가 − 확정가), 무산이면 전액이다.
     * 차액이 0원이면(할인율 0% 로 성사) PG 를 부르지 않는다 — 토스는 0원 취소를 거부한다.
     */
    private fun refundOne(participant: ConfirmedParticipant, result: CloseResult): Boolean {
        val amount: Int
        val reason: RefundReason

        if (result.succeeded) {
            val finalAmount = Money.discounted(participant.listAmount, result.finalDiscountRate)
            amount = participationClosingService.markAdjusting(participant.participationId, finalAmount)
            reason = RefundReason.TIER_ADJUST
        } else {
            amount = participationClosingService.markRefunding(participant.participationId)
            reason = RefundReason.DEAL_FAILED
        }

        if (amount <= 0) {
            participationClosingService.markRefundSettled(participant.participationId, succeeded = true)
            return true
        }

        val outcome = refundService.refund(RefundCommand(participant.orderId, amount, reason))
        participationClosingService.markRefundSettled(participant.participationId, outcome.succeeded)
        return outcome.succeeded
    }
}
