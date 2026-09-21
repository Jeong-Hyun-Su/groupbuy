package com.groupbuy.payment.domain

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.InvalidStateTransitionException
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
 * 환불. 설계서 2.6 환불 생명주기.
 *
 * `idempotency_key` 가 UNIQUE 다 — 같은 환불이 두 번 생성되지 않고(DB), PG 호출 시 헤더로 넘겨
 * 같은 취소가 두 번 반영되지 않는다(PG). 이 두 겹이 R6 의 방어선이다.
 *
 * Phase 1 은 마감 트랜잭션 안에서 동기로 실행한다 (실패하면 마감 전체가 롤백 — 의도된 한계).
 * Phase 3 에서 PENDING 으로 쌓고 워커가 지수 백오프로 처리한다.
 */
@Entity
@Table(name = "refunds")
class Refund private constructor(
    @Column(name = "payment_id", nullable = false)
    val paymentId: Long,

    @Column(nullable = false)
    val amount: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val reason: RefundReason,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: RefundStatus = RefundStatus.PENDING
        protected set

    /**
     * PG Idempotency-Key. `refund-{paymentId}-{reason}` 형태다.
     *
     * 설계서는 `refund-{refund_id}` 를 적었지만 id 는 insert 후에야 나온다.
     * 같은 결제에 같은 사유의 환불은 한 번뿐이므로 (paymentId, reason) 이 자연 키가 된다 —
     * 이렇게 하면 "환불 행을 만들다 실패하고 재시도" 해도 같은 키가 나와 중복이 막힌다.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    val idempotencyKey: String = "refund-$paymentId-${reason.name.lowercase()}"

    @Column(name = "pg_cancel_key", length = 200)
    var pgCancelKey: String? = null
        protected set

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0
        protected set

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null
        protected set

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null
        protected set

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set

    val isCompleted: Boolean get() = status == RefundStatus.COMPLETED

    /** 워커가 점유. PENDING → PROCESSING (Phase 3) */
    fun startProcessing() {
        transition(from = setOf(RefundStatus.PENDING), to = RefundStatus.PROCESSING)
        attemptCount += 1
    }

    /** PG 취소 성공 */
    fun complete(cancelKey: String) {
        if (status == RefundStatus.COMPLETED) return          // 멱등
        transition(from = setOf(RefundStatus.PENDING, RefundStatus.PROCESSING, RefundStatus.MANUAL_REQUIRED), to = RefundStatus.COMPLETED)
        pgCancelKey = cancelKey
        lastError = null
        nextRetryAt = null
    }

    /** 일시 오류 — 백오프 후 재시도 (Phase 3) */
    fun scheduleRetry(nextRetryAt: Instant, error: String) {
        transition(from = setOf(RefundStatus.PROCESSING), to = RefundStatus.PENDING)
        this.nextRetryAt = nextRetryAt
        lastError = error.take(MAX_ERROR_LENGTH)
    }

    /** 재시도 한도 초과 또는 영구 오류 — 사람이 처리해야 한다 */
    fun requireManual(error: String) {
        transition(from = setOf(RefundStatus.PENDING, RefundStatus.PROCESSING), to = RefundStatus.MANUAL_REQUIRED)
        lastError = error.take(MAX_ERROR_LENGTH)
        nextRetryAt = null
    }

    private fun transition(from: Set<RefundStatus>, to: RefundStatus) {
        if (status !in from) throw InvalidStateTransitionException("환불 상태 전이 불가: $status → $to")
        status = to
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 2000

        fun pending(paymentId: Long, amount: Int, reason: RefundReason): Refund {
            if (amount <= 0) throw DomainException(ErrorCode.INVALID_REQUEST, "환불 금액은 0보다 커야 합니다.")
            return Refund(paymentId, amount, reason)
        }
    }
}
