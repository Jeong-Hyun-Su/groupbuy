package com.groupbuy.payment.application

import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.payment.domain.PaymentGateway
import com.groupbuy.payment.domain.PaymentRepository
import com.groupbuy.payment.domain.Refund
import com.groupbuy.payment.domain.RefundReason
import com.groupbuy.payment.domain.RefundRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 환불 대상 하나 — 마감 오케스트레이터가 참여자별로 넘긴다 */
data class RefundCommand(
    val orderId: Long,
    val amount: Int,
    val reason: RefundReason,
)

data class RefundOutcome(
    val orderId: Long,
    val refundId: Long?,
    val amount: Int,
    val succeeded: Boolean,
    val error: String? = null,
)

/**
 * 환불 실행. Phase 1 은 마감 트랜잭션 안에서 **동기** 호출이다 (설계서 11 Phase 1 — 의도된 한계).
 *
 * 한계가 분명하다. 참여자 100명이면 PG 호출 100번이 한 트랜잭션에 묶이고,
 * 하나만 실패해도 마감 전체가 롤백된다. 이 붕괴가 Phase 3(환불 워커 + 지수 백오프)의 동기다.
 *
 * 멱등성은 지금부터 지킨다.
 *   - 같은 (결제, 사유) 로 이미 완료된 환불이 있으면 PG 를 부르지 않는다
 *   - PG 호출에 `Idempotency-Key` 를 넘긴다
 */
@Service
class RefundService(
    private val refundRepository: RefundRepository,
    private val paymentRepository: PaymentRepository,
    private val paymentGateway: PaymentGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 환불을 생성하고 즉시 PG 취소를 호출한다.
     * 실패하면 예외를 던져 호출자(마감 트랜잭션)를 롤백시킨다.
     */
    @Transactional
    fun refund(command: RefundCommand): RefundOutcome {
        val payment = paymentRepository.findAllByOrderIds(listOf(command.orderId)).firstOrNull()
            ?: throw NotFoundException(ErrorCode.PAYMENT_NOT_FOUND, "주문의 결제를 찾을 수 없습니다: orderId=${command.orderId}")

        if (!payment.status.cancellable) {
            // 재시도해도 결과가 같다 — 예외로 마감 전체를 막지 않고, 운영자가 볼 수 있게 크게 남긴다
            log.error("refund skipped, payment not cancellable (수동 확인 필요): orderId={} status={}", command.orderId, payment.status)
            return RefundOutcome(command.orderId, null, command.amount, succeeded = false, error = "취소 가능한 결제가 아닙니다.")
        }

        val paymentId = payment.id ?: throw IllegalStateException("영속화되지 않은 결제입니다.")
        val refund = existingOrNew(paymentId, command)

        // 이미 완료된 환불이면 PG 를 다시 부르지 않는다 (R6)
        if (refund.isCompleted) {
            return RefundOutcome(command.orderId, refund.id, refund.amount, succeeded = true)
        }

        val result = paymentGateway.cancel(
            PaymentGateway.CancelRequest(
                paymentKey = payment.requirePaymentKey(),
                cancelAmount = refund.amount,
                reason = refund.reason.name,
                idempotencyKey = refund.idempotencyKey,
            ),
        )
        refund.complete(result.cancelKey)

        // 전액이면 CANCELED, 일부면 PARTIALLY_CANCELED
        if (refund.amount >= payment.amount) payment.markCanceled() else payment.markPartiallyCanceled()

        log.info("refund completed: orderId={} amount={} reason={}", command.orderId, refund.amount, refund.reason)
        return RefundOutcome(command.orderId, refund.id, refund.amount, succeeded = true)
    }

    /** 같은 (결제, 사유) 환불이 이미 있으면 재사용한다 — 재시도해도 행이 늘지 않는다 */
    private fun existingOrNew(paymentId: Long, command: RefundCommand): Refund {
        val candidate = Refund.pending(paymentId, command.amount, command.reason)
        return refundRepository.findByIdempotencyKey(candidate.idempotencyKey)
            ?: refundRepository.save(candidate)
    }
}
