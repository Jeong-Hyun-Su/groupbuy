package com.groupbuy.participation.domain

import com.groupbuy.common.domain.AggregateRoot
import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.InvalidStateTransitionException
import com.groupbuy.participation.domain.event.ParticipationConfirmed
import com.groupbuy.participation.domain.event.ParticipationExpired
import com.groupbuy.participation.domain.event.ParticipationReserved
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
import java.time.Duration
import java.time.Instant

/**
 * 참여 애그리거트. 설계서 2.5 참여 생명주기.
 * "특정 사용자가 특정 딜에 자리를 차지한 사실" — 딜당 사용자당 1행 (UNIQUE(deal_id, user_id), R2).
 *
 * 선점(RESERVED)은 TTL 이 있다. 결제 없이 자리를 영구 점유하지 못하게 하기 위함.
 * 만료·취소된 행은 같은 사용자가 다시 선점할 때 재사용한다 (reserveAgain) — UNIQUE 제약 때문에 새 행을 만들 수 없다.
 */
@Entity
@Table(name = "participations")
class Participation private constructor(
    @Column(name = "deal_id", nullable = false)
    val dealId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    orderId: Long,
    reservedAt: Instant,
    reservationExpiresAt: Instant,
) : AggregateRoot() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** 현재 유효한 주문. 재선점 시 새 주문으로 바뀐다 */
    @Column(name = "order_id", nullable = false)
    var orderId: Long = orderId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ParticipationStatus = ParticipationStatus.RESERVED
        protected set

    @Column(name = "reserved_at", nullable = false)
    var reservedAt: Instant = reservedAt
        protected set

    @Column(name = "reservation_expires_at", nullable = false)
    var reservationExpiresAt: Instant = reservationExpiresAt
        protected set

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null
        protected set

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set

    // ---------- 조회 ----------

    val isReserved: Boolean get() = status == ParticipationStatus.RESERVED

    /** 정원 계산에 포함되는가 (RESERVED + CONFIRMED) */
    val occupiesSlot: Boolean get() = status.occupiesSlot

    /** 만료·취소되어 같은 사용자가 다시 선점할 수 있는가 */
    val canReserveAgain: Boolean
        get() = status == ParticipationStatus.EXPIRED || status == ParticipationStatus.CANCELLED

    fun isReservationExpired(now: Instant): Boolean = isReserved && !now.isBefore(reservationExpiresAt)

    // ---------- 상태 전이 ----------

    /** 결제 승인. RESERVED → CONFIRMED. 선점 만료 후에는 승인할 수 없다 */
    fun confirm(now: Instant) {
        if (status != ParticipationStatus.RESERVED) {
            throw InvalidStateTransitionException("참여 상태 전이 불가: $status → CONFIRMED")
        }
        if (!now.isBefore(reservationExpiresAt)) throw DomainException(ErrorCode.RESERVATION_EXPIRED)
        status = ParticipationStatus.CONFIRMED
        confirmedAt = now
        registerEvent(ParticipationConfirmed(dealId = dealId, userId = userId, participationId = requireId(), occurredAt = now))
    }

    /** 결제 제한시간 초과. RESERVED → EXPIRED (UC-06) */
    fun expire(now: Instant) {
        transition(from = setOf(ParticipationStatus.RESERVED), to = ParticipationStatus.EXPIRED)
        registerEvent(ParticipationExpired(dealId = dealId, userId = userId, participationId = requireId(), occurredAt = now))
    }

    /** 사용자 자진 취소 (딜 OPEN 중). CONFIRMED → CANCELLED (UC-05, Phase 3) */
    fun cancel() {
        transition(from = setOf(ParticipationStatus.CONFIRMED), to = ParticipationStatus.CANCELLED)
    }

    /** 딜 성사 → 차액 환불 시작 */
    fun startAdjusting() {
        transition(from = setOf(ParticipationStatus.CONFIRMED), to = ParticipationStatus.ADJUSTING)
    }

    /** 차액 환불 완료 */
    fun finalize() {
        transition(from = setOf(ParticipationStatus.ADJUSTING), to = ParticipationStatus.FINALIZED)
    }

    /** 딜 무산 → 전액 환불 시작 */
    fun startRefunding() {
        transition(from = setOf(ParticipationStatus.CONFIRMED), to = ParticipationStatus.REFUNDING)
    }

    /** 전액 환불 완료 */
    fun markRefunded() {
        transition(from = setOf(ParticipationStatus.REFUNDING), to = ParticipationStatus.REFUNDED)
    }

    /** 만료·취소된 자리를 같은 사용자가 새 주문으로 다시 선점. EXPIRED | CANCELLED → RESERVED */
    fun reserveAgain(orderId: Long, now: Instant, ttl: Duration) {
        requirePositive(ttl)
        transition(from = setOf(ParticipationStatus.EXPIRED, ParticipationStatus.CANCELLED), to = ParticipationStatus.RESERVED)
        this.orderId = orderId
        reservedAt = now
        reservationExpiresAt = now.plus(ttl)
        confirmedAt = null
        registerEvent(ParticipationReserved(dealId = dealId, userId = userId, occurredAt = now))
    }

    private fun transition(from: Set<ParticipationStatus>, to: ParticipationStatus) {
        if (status !in from) throw InvalidStateTransitionException("참여 상태 전이 불가: $status → $to")
        status = to
    }

    private fun requireId(): Long = id ?: throw IllegalStateException("영속화되지 않은 참여입니다.")

    companion object {
        /** 자리 선점. 정원·중복 검사는 호출자(application) 가 딜 잠금 아래에서 끝낸 뒤 호출한다 */
        fun reserve(dealId: Long, userId: Long, orderId: Long, now: Instant, ttl: Duration): Participation {
            requirePositive(ttl)
            return Participation(dealId, userId, orderId, now, now.plus(ttl)).apply {
                registerEvent(ParticipationReserved(dealId = dealId, userId = userId, occurredAt = now))
            }
        }

        private fun requirePositive(ttl: Duration) {
            if (ttl.isZero || ttl.isNegative) throw IllegalArgumentException("선점 TTL 은 양수여야 합니다.")
        }
    }
}
