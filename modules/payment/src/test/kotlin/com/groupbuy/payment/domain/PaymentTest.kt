package com.groupbuy.payment.domain

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.InvalidStateTransitionException
import com.groupbuy.payment.domain.event.PaymentApproved
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class PaymentTest {

    private val now: Instant = Instant.parse("2026-09-20T02:00:00Z")

    private fun ready(amount: Int = 100_000): Payment =
        Payment.ready(orderId = 1, orderNo = "GB-1-42-abc", amount = amount, pgProvider = "TOSS")
            .also { ReflectionTestUtils.setField(it, "id", 1L) }

    @Nested
    inner class `생성` {

        @Test
        fun `READY 상태로 만들어지고 결제 키는 아직 없다`() {
            val payment = ready()

            assertThat(payment.status).isEqualTo(PaymentStatus.READY)
            assertThat(payment.pgPaymentKey).isNull()
            assertThat(payment.approvedAt).isNull()
            assertThat(payment.isApproved).isFalse()
        }

        @Test
        fun `금액은 0보다 커야 한다`() {
            assertThatThrownBy { Payment.ready(1, "GB-1-1-x", 0, "TOSS") }
                .isInstanceOf(DomainException::class.java)
        }
    }

    @Nested
    inner class `승인` {

        @Test
        fun `승인하면 APPROVED 가 되고 결제 키와 승인 이벤트가 남는다`() {
            val payment = ready()

            val changed = payment.approve("pay_key_1", 100_000, now)

            assertThat(changed).isTrue()
            assertThat(payment.status).isEqualTo(PaymentStatus.APPROVED)
            assertThat(payment.pgPaymentKey).isEqualTo("pay_key_1")
            assertThat(payment.approvedAt).isEqualTo(now)
            assertThat(payment.pollEvents()).singleElement().isInstanceOf(PaymentApproved::class.java)
        }

        @Test
        fun `같은 결제 키로 다시 승인하면 아무것도 바뀌지 않고 false 다 (R6)`() {
            val payment = ready().apply { approve("pay_key_1", 100_000, now); pollEvents() }

            val changed = payment.approve("pay_key_1", 100_000, now.plusSeconds(5))

            assertThat(changed).isFalse()
            assertThat(payment.approvedAt).isEqualTo(now)          // 최초 승인 시각을 지킨다
            assertThat(payment.pollEvents()).isEmpty()             // 이벤트도 두 번 나가지 않는다
        }

        @Test
        fun `다른 결제 키로 승인하려 하면 거부한다`() {
            val payment = ready().apply { approve("pay_key_1", 100_000, now) }

            assertThatThrownBy { payment.approve("pay_key_2", 100_000, now) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_ALREADY_APPROVED)
        }

        @Test
        fun `승인 금액이 주문 금액과 다르면 거부한다 (R4)`() {
            assertThatThrownBy { ready(100_000).approve("pay_key_1", 90_000, now) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH)
        }

        @Test
        fun `실패한 결제는 승인할 수 없다`() {
            val payment = ready().apply { markFailed() }

            assertThatThrownBy { payment.approve("pay_key_1", 100_000, now) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }
    }

    @Nested
    inner class `취소` {

        @Test
        fun `승인된 결제만 취소 상태로 갈 수 있다`() {
            val approved = ready().apply { approve("k", 100_000, now) }

            approved.markPartiallyCanceled()
            assertThat(approved.status).isEqualTo(PaymentStatus.PARTIALLY_CANCELED)
            assertThat(approved.status.cancellable).isTrue()

            approved.markCanceled()
            assertThat(approved.status).isEqualTo(PaymentStatus.CANCELED)
            assertThat(approved.status.cancellable).isFalse()
        }

        @Test
        fun `승인 전에는 취소할 수 없고 결제 키도 꺼낼 수 없다`() {
            val payment = ready()

            assertThatThrownBy { payment.markCanceled() }.isInstanceOf(InvalidStateTransitionException::class.java)
            assertThatThrownBy { payment.requirePaymentKey() }.isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `승인 후에는 취소에 쓸 결제 키를 준다`() {
            val payment = ready().apply { approve("pay_key_1", 100_000, now) }

            assertThat(payment.requirePaymentKey()).isEqualTo("pay_key_1")
        }
    }
}
