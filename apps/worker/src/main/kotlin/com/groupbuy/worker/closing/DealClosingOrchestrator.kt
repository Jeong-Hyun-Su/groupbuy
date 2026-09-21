package com.groupbuy.worker.closing

import com.groupbuy.deal.application.DealCommandService
import com.groupbuy.deal.domain.CloseResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 딜 하나의 마감 결과 */
data class ClosingReport(
    val dealId: Long,
    val closed: Boolean,
    val result: CloseResult? = null,
    val refundedCount: Int = 0,
    val failedCount: Int = 0,
    val skippedReason: String? = null,
)

/**
 * 마감 오케스트레이터 (설계서 10.3).
 *
 * deal · participation · payment 세 모듈을 조합하므로 모듈 안이 아니라 apps/worker 에 둔다
 * (모듈 간 직접 호출 금지 — ArchUnit 이 강제한다).
 *
 * 순서가 규칙을 만든다.
 *   1. `beginClosing` — OPEN → CLOSING 점유. 여기서 신규 참여가 막히고 중복 마감도 막힌다 (R8)
 *   2. 확정 인원 집계 — CONFIRMED 만 센다. 결제 중이던 RESERVED 는 제외 (ADR-07)
 *   3. `finishClosing` — 확정 인원으로 할인율을 정한다. 전원 동일 (R3)
 *   4. 환불 — 성사면 차액, 무산이면 전액 (R4, R5)
 *
 * 트랜잭션은 둘로 나뉜다.
 *   - 1번은 즉시 커밋한다. 그래야 다른 인스턴스가 이 딜을 건드리지 않는다
 *   - 2~4번은 `DealSettlementProcessor` 가 한 트랜잭션으로 묶는다
 *
 * **Phase 1 의 의도된 한계**: 4번이 동기다. 참여자 100명이면 PG 호출 100번이 한 트랜잭션에 묶여
 * 그동안 DB 커넥션을 붙잡는다. LT-04 로 이 붕괴 지점을 측정해 Phase 3(환불 워커)의 근거로 삼는다.
 */
@Component
class DealClosingOrchestrator(
    private val dealCommandService: DealCommandService,
    private val settlementProcessor: DealSettlementProcessor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 마감 대상을 훑어 하나씩 닫는다. 한 딜의 실패가 다른 딜을 막지 않는다 */
    fun closeDueDeals(limit: Int = 100): List<ClosingReport> =
        dealCommandService.findDueToClose(limit).map { dealId ->
            runCatching { close(dealId) }
                .getOrElse { e ->
                    log.error("deal closing failed: dealId={}", dealId, e)
                    ClosingReport(dealId, closed = false, skippedReason = e.message ?: e::class.simpleName)
                }
        }

    fun close(dealId: Long): ClosingReport {
        // 점유 — 실패하면 다른 인스턴스가 이미 가져간 것이다 (R8)
        if (!dealCommandService.beginClosing(dealId)) {
            log.debug("deal already claimed by another instance: dealId={}", dealId)
            return ClosingReport(dealId, closed = false, skippedReason = "이미 마감 처리 중")
        }
        return settlementProcessor.settle(dealId)
    }
}
