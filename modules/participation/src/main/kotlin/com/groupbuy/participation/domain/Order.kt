package com.groupbuy.participation.domain

import com.groupbuy.common.domain.Money
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
import java.util.UUID

/**
 * 주문. 참여 1건당 1건이며 정가(listAmount)로 생성된다 (ADR-03 선결제).
 * 마감 후 finalize 로 확정 금액이 기록되고, 차액은 payment 모듈이 환불한다 (R4).
 */
@Entity(name = "PurchaseOrder")   // HQL 에서 Order 는 예약어라 엔티티 이름을 따로 준다
@Table(name = "orders")
class Order private constructor(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "deal_id", nullable = false)
    val dealId: Long,

    /** PG 에 전달하는 주문번호. 토스 규격: 6~64자, 영문·숫자·-·_ */
    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    val orderNo: String,

    @Column(name = "list_amount", nullable = false)
    val listAmount: Int,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.READY
        protected set

    @Column(name = "final_amount")
    var finalAmount: Int? = null
        protected set

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set

    /** 결제 승인. READY → PAID */
    fun markPaid() {
        transition(from = setOf(OrderStatus.READY), to = OrderStatus.PAID)
    }

    /** 마감 후 확정 금액 반영. PAID → FINALIZED. 확정 금액은 정가를 넘을 수 없다 (R4) */
    fun finalize(finalAmount: Int) {
        if (finalAmount < 0 || finalAmount > listAmount) {
            throw DomainException(ErrorCode.INVALID_REQUEST, "확정 금액은 0 이상 정가 이하여야 합니다.")
        }
        transition(from = setOf(OrderStatus.PAID), to = OrderStatus.FINALIZED)
        this.finalAmount = finalAmount
    }

    /** 선점 만료 · 자진 취소 · 무산. READY | PAID → CANCELED */
    fun cancel() {
        transition(from = setOf(OrderStatus.READY, OrderStatus.PAID), to = OrderStatus.CANCELED)
    }

    /** 차액 환불액 = 결제액 − 확정액. FINALIZED 에서만 계산 가능 */
    fun tierRefundAmount(): Int {
        val final = finalAmount ?: throw InvalidStateTransitionException("확정 전에는 차액을 계산할 수 없습니다.")
        return Money.tierRefund(listAmount, final)
    }

    private fun transition(from: Set<OrderStatus>, to: OrderStatus) {
        if (status !in from) throw InvalidStateTransitionException("주문 상태 전이 불가: $status → $to")
        status = to
    }

    companion object {
        fun create(userId: Long, dealId: Long, listAmount: Int): Order {
            if (listAmount < 0) throw DomainException(ErrorCode.INVALID_REQUEST, "주문 금액은 0 이상이어야 합니다.")
            return Order(userId, dealId, generateOrderNo(dealId, userId), listAmount)
        }

        internal fun generateOrderNo(dealId: Long, userId: Long): String =
            "GB-$dealId-$userId-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
    }
}
