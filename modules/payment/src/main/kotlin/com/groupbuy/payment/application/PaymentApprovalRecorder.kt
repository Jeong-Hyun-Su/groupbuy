package com.groupbuy.payment.application

import com.groupbuy.common.time.TimeProvider
import com.groupbuy.participation.application.ParticipationConfirmService
import com.groupbuy.payment.domain.Payment
import com.groupbuy.payment.domain.PaymentGateway
import com.groupbuy.payment.domain.PaymentRepository
import com.groupbuy.payment.domain.PaymentStatus
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * PG 승인 결과를 DB 에 반영하는 트랜잭션 경계.
 *
 * `PaymentConfirmService` 와 분리한 이유는 두 가지다.
 *   1. PG 호출(외부 I/O)은 트랜잭션 밖, DB 반영은 트랜잭션 안 — 경계가 클래스로 드러난다
 *   2. 같은 클래스 안에서 `@Transactional` 메서드를 호출하면 프록시를 타지 않아 트랜잭션이 안 열린다
 */
@Component
class PaymentApprovalRecorder(
    private val paymentRepository: PaymentRepository,
    private val participationConfirmService: ParticipationConfirmService,
    private val timeProvider: TimeProvider,
    private val paymentGateway: PaymentGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 승인 반영. payment APPROVED + participation CONFIRMED + order PAID 가 한 트랜잭션이다.
     *
     * @return 이번 호출로 승인이 기록됐으면 true. 이미 승인된 건이면 false (R6 멱등)
     */
    @Transactional
    fun record(orderId: Long, orderNo: String, amount: Int, paymentKey: String, approvedAmount: Int): RecordedApproval {
        val payment = lockOrCreate(orderId, orderNo, amount)
        val changed = payment.approve(paymentKey, approvedAmount, timeProvider.now())

        if (changed) {
            participationConfirmService.confirmByOrder(orderId)
            log.info("payment approved: orderNo={} amount={}", orderNo, approvedAmount)
        }
        return RecordedApproval(amount = payment.amount, status = payment.status.name, newlyApproved = changed)
    }

    /**
     * 승인이 확정적으로 실패했음을 기록한다.
     * 별도 트랜잭션이라 호출자의 롤백에 휩쓸리지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(orderId: Long, orderNo: String, amount: Int) {
        val payment = lockOrCreate(orderId, orderNo, amount)
        if (payment.status == PaymentStatus.READY) payment.markFailed()
    }

    /** 결제 행을 잠그고 가져온다. 없으면 만든다 — insert 경쟁은 `order_no` UNIQUE 가 정리한다 */
    private fun lockOrCreate(orderId: Long, orderNo: String, amount: Int): Payment {
        paymentRepository.findByOrderNoForUpdate(orderNo)?.let { return it }
        return try {
            paymentRepository.save(Payment.ready(orderId, orderNo, amount, paymentGateway.providerName()))
        } catch (e: DataIntegrityViolationException) {
            paymentRepository.findByOrderNoForUpdate(orderNo)
                ?: throw IllegalStateException("결제 행 생성 경쟁 후에도 행을 찾지 못했습니다: $orderNo", e)
        }
    }

    data class RecordedApproval(val amount: Int, val status: String, val newlyApproved: Boolean)
}
