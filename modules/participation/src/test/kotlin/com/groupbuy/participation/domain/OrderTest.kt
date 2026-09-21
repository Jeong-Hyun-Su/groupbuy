package com.groupbuy.participation.domain

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.InvalidStateTransitionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderTest {

    @Test
    fun `정가로 READY 상태 주문이 생성되고 주문번호는 PG 규격을 지킨다`() {
        val order = Order.create(userId = 7, dealId = 3, listAmount = 100_000)

        assertThat(order.status).isEqualTo(OrderStatus.READY)
        assertThat(order.listAmount).isEqualTo(100_000)
        assertThat(order.finalAmount).isNull()
        assertThat(order.orderNo).startsWith("GB-3-7-").matches("[A-Za-z0-9_-]{6,64}")
    }

    @Test
    fun `주문번호는 매번 다르다`() {
        val a = Order.generateOrderNo(1, 1)
        val b = Order.generateOrderNo(1, 1)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `READY → PAID → FINALIZED 로 전이되고 차액을 계산한다 (R4)`() {
        val order = Order.create(1, 1, 100_000)

        order.markPaid()
        assertThat(order.status).isEqualTo(OrderStatus.PAID)

        order.finalize(80_000)
        assertThat(order.status).isEqualTo(OrderStatus.FINALIZED)
        assertThat(order.finalAmount).isEqualTo(80_000)
        assertThat(order.tierRefundAmount()).isEqualTo(20_000)
    }

    @Test
    fun `결제 전에는 확정할 수 없다`() {
        assertThatThrownBy { Order.create(1, 1, 100_000).finalize(80_000) }
            .isInstanceOf(InvalidStateTransitionException::class.java)
    }

    @Test
    fun `확정 금액은 정가를 넘을 수 없다`() {
        val order = Order.create(1, 1, 100_000).apply { markPaid() }

        assertThatThrownBy { order.finalize(100_001) }.isInstanceOf(DomainException::class.java)
        assertThatThrownBy { order.finalize(-1) }.isInstanceOf(DomainException::class.java)
    }

    @Test
    fun `확정 전에는 차액을 계산할 수 없다`() {
        assertThatThrownBy { Order.create(1, 1, 100_000).tierRefundAmount() }
            .isInstanceOf(InvalidStateTransitionException::class.java)
    }

    @Test
    fun `READY 와 PAID 는 취소할 수 있고 FINALIZED 는 취소할 수 없다`() {
        Order.create(1, 1, 100_000).apply { cancel() }.also { assertThat(it.status).isEqualTo(OrderStatus.CANCELED) }
        Order.create(1, 1, 100_000).apply { markPaid(); cancel() }.also { assertThat(it.status).isEqualTo(OrderStatus.CANCELED) }

        val finalized = Order.create(1, 1, 100_000).apply { markPaid(); finalize(80_000) }
        assertThatThrownBy { finalized.cancel() }.isInstanceOf(InvalidStateTransitionException::class.java)
    }
}
