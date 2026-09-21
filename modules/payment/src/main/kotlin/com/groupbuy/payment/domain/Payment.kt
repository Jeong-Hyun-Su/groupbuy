package com.groupbuy.payment.domain

import com.groupbuy.common.domain.AggregateRoot
import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.InvalidStateTransitionException
import com.groupbuy.payment.domain.event.PaymentApproved
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

/**
 * 결제. 주문 1건당 1행이며 `order_no` 가 UNIQUE 다 — 이 제약이 R6(이중 승인 방지)의 최종 방어선이다.
 *
 * 승인은 confirm(클라이언트) 과 webhook(PG) 양쪽에서 들어온다 (설계서 9.2).
 * 먼저 온 쪽이 승인하고 나머지는 `approve` 가 멱등하게 skip 한다.
 */
@Entity
@Table(name = "payments")
class Payment private constructor(
    @Column(name = "order_id", nullable = false)
    val orderId: Long,

    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    val orderNo: String,

    @Column(name = "pg_provider", nullable = false, length = 20)
    val pgProvider: String,

    @Column(nullable = false)
    val amount: Int,
) : AggregateRoot() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** PG 가 발급한 결제 키. 승인 전에는 null, 취소 시 사용 */
    @Column(name = "pg_payment_key", unique = true, length = 200)
    var pgPaymentKey: String? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: PaymentStatus = PaymentStatus.READY
        protected set

    @Column(name = "approved_at")
    var approvedAt: Instant? = null
        protected set

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set

    val isApproved: Boolean get() = status == PaymentStatus.APPROVED

    /**
     * 승인 기록. READY → APPROVED.
     *
     * 이미 같은 `paymentKey` 로 승인된 건이면 아무것도 바꾸지 않고 false 를 돌려준다 (R6 멱등).
     * 다른 키로 승인된 건이면 같은 주문에 두 번 결제된 것이므로 거부한다.
     */
    fun approve(paymentKey: String, approvedAmount: Int, now: Instant): Boolean {
        if (status == PaymentStatus.APPROVED) {
            if (pgPaymentKey != paymentKey) {
                throw DomainException(ErrorCode.PAYMENT_ALREADY_APPROVED, "이미 다른 결제로 승인된 주문입니다.")
            }
            return false
        }
        if (status != PaymentStatus.READY) {
            throw InvalidStateTransitionException("결제 상태 전이 불가: $status → APPROVED")
        }
        if (approvedAmount != amount) {
            throw DomainException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "승인 금액이 주문 금액과 다릅니다.")
        }

        pgPaymentKey = paymentKey
        status = PaymentStatus.APPROVED
        approvedAt = now
        registerEvent(PaymentApproved(paymentId = requireId(), orderId = orderId, orderNo = orderNo, amount = amount, occurredAt = now))
        return true
    }

    /** 승인 실패 기록. READY → FAILED. 재시도 가능한 오류에는 쓰지 않는다 */
    fun markFailed() {
        transition(from = setOf(PaymentStatus.READY), to = PaymentStatus.FAILED)
    }

    /** 차액 부분취소 완료 (Phase 3) */
    fun markPartiallyCanceled() {
        transition(from = setOf(PaymentStatus.APPROVED, PaymentStatus.PARTIALLY_CANCELED), to = PaymentStatus.PARTIALLY_CANCELED)
    }

    /** 전액 취소 완료 */
    fun markCanceled() {
        transition(from = setOf(PaymentStatus.APPROVED, PaymentStatus.PARTIALLY_CANCELED), to = PaymentStatus.CANCELED)
    }

    /** 취소 요청에 쓸 PG 결제 키. 승인 전이면 오류 */
    fun requirePaymentKey(): String =
        pgPaymentKey ?: throw InvalidStateTransitionException("승인되지 않은 결제입니다.")

    private fun transition(from: Set<PaymentStatus>, to: PaymentStatus) {
        if (status !in from) throw InvalidStateTransitionException("결제 상태 전이 불가: $status → $to")
        status = to
    }

    private fun requireId(): Long = id ?: throw IllegalStateException("영속화되지 않은 결제입니다.")

    companion object {
        fun ready(orderId: Long, orderNo: String, amount: Int, pgProvider: String): Payment {
            if (amount <= 0) throw DomainException(ErrorCode.INVALID_REQUEST, "결제 금액은 0보다 커야 합니다.")
            return Payment(orderId, orderNo, pgProvider, amount)
        }
    }
}
