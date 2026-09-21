package com.groupbuy.payment.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.participation.application.OrderQueryService
import com.groupbuy.payment.domain.PaymentGateway
import com.groupbuy.payment.domain.PaymentGatewayException
import com.groupbuy.payment.domain.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class ConfirmPaymentCommand(
    val paymentKey: String,
    val orderNo: String,
    val amount: Int,
)

data class ConfirmPaymentResult(
    val orderNo: String,
    val amount: Int,
    val status: String,
    /** 이번 호출이 실제로 승인했는가. false 면 이미 승인된 건을 멱등하게 확인만 한 것 */
    val newlyApproved: Boolean,
)

/**
 * 결제 승인 (설계서 9.2, 10.1). confirm(클라이언트) 과 webhook(PG) 이 같은 이 경로를 탄다.
 *
 * 승인은 세 단계다.
 *   1. 사전 검증 — 주문 존재·금액 일치. PG 를 호출하기 전에 틀린 요청을 걷어낸다
 *   2. PG 승인 호출 — 외부 I/O. **트랜잭션 밖**에서 한다 (DB 커넥션을 PG 응답만큼 붙잡지 않기 위해)
 *   3. 결과 반영 — `PaymentApprovalRecorder` 가 한 트랜잭션으로 처리
 *
 * 2번과 3번 사이에 프로세스가 죽으면 "PG 는 승인했는데 우리 DB 는 모르는" 상태가 된다.
 * Phase 1 은 웹훅 재수신으로 복구한다 (웹훅이 같은 경로를 타고 3번을 다시 실행).
 * Phase 3 에서 승인 전 `READY` 행을 먼저 커밋해 고아 승인을 추적 가능하게 만든다.
 */
@Service
class PaymentConfirmService(
    private val paymentRepository: PaymentRepository,
    private val paymentGateway: PaymentGateway,
    private val orderQueryService: OrderQueryService,
    private val approvalRecorder: PaymentApprovalRecorder,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun confirm(command: ConfirmPaymentCommand): ConfirmPaymentResult {
        val order = orderQueryService.getByOrderNo(command.orderNo)

        // R4 방어: 클라이언트가 보낸 금액이 주문 금액과 다르면 PG 를 호출하지 않는다
        if (command.amount != order.listAmount) {
            throw DomainException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "요청 금액이 주문 금액과 다릅니다.")
        }

        // 이미 승인된 건이면 PG 를 다시 호출하지 않는다 (R6). confirm 과 webhook 이 둘 다 와도 한 번만 나간다
        alreadyApproved(command)?.let { return it }

        val approved = try {
            paymentGateway.approve(
                PaymentGateway.ApproveRequest(
                    paymentKey = command.paymentKey,
                    orderNo = command.orderNo,
                    amount = command.amount,
                ),
            )
        } catch (e: PaymentGatewayException) {
            handleApprovalFailure(command, order.orderId, e)
        }

        val recorded = approvalRecorder.record(
            orderId = order.orderId,
            orderNo = command.orderNo,
            amount = command.amount,
            paymentKey = approved.paymentKey,
            approvedAmount = approved.approvedAmount,
        )
        return ConfirmPaymentResult(command.orderNo, recorded.amount, recorded.status, recorded.newlyApproved)
    }

    /** 이미 APPROVED 인지 확인. 맞으면 멱등 응답을 만든다 */
    private fun alreadyApproved(command: ConfirmPaymentCommand): ConfirmPaymentResult? {
        val existing = paymentRepository.findByOrderNo(command.orderNo) ?: return null
        if (!existing.isApproved) return null
        if (existing.pgPaymentKey != command.paymentKey) {
            throw DomainException(ErrorCode.PAYMENT_ALREADY_APPROVED, "이미 다른 결제로 승인된 주문입니다.")
        }
        return ConfirmPaymentResult(command.orderNo, existing.amount, existing.status.name, newlyApproved = false)
    }

    /**
     * 승인 실패 처리.
     * - 영구 오류(카드 거절 등): FAILED 로 기록하고 사용자에게 알린다. 선점은 TTL 만료로 풀린다 (UC-06)
     * - 일시 오류(타임아웃 등): 아무것도 기록하지 않는다. 실제로 승인됐을 수 있어 FAILED 로 못 박으면 위험하다
     */
    private fun handleApprovalFailure(command: ConfirmPaymentCommand, orderId: Long, e: PaymentGatewayException): Nothing {
        if (e.retryable) {
            log.warn("payment approval uncertain: orderNo={} code={} msg={}", command.orderNo, e.pgCode, e.message)
            throw DomainException(ErrorCode.PAYMENT_PENDING, ErrorCode.PAYMENT_PENDING.defaultMessage, e)
        }

        log.info("payment approval rejected: orderNo={} code={} msg={}", command.orderNo, e.pgCode, e.message)
        approvalRecorder.recordFailure(orderId, command.orderNo, command.amount)
        throw DomainException(ErrorCode.PAYMENT_APPROVAL_FAILED, e.message, e)
    }
}
