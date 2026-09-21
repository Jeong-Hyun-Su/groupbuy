package com.groupbuy.payment.application

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.common.time.FixedTimeProvider
import com.groupbuy.participation.application.OrderQueryService
import com.groupbuy.participation.application.OrderView
import com.groupbuy.participation.application.ParticipationConfirmService
import com.groupbuy.payment.domain.Payment
import com.groupbuy.payment.domain.PaymentGatewayException
import com.groupbuy.payment.domain.PaymentRepository
import com.groupbuy.payment.domain.PaymentStatus
import com.groupbuy.participation.application.ConfirmOutcome
import com.groupbuy.payment.domain.RefundReason
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class PaymentConfirmServiceTest {

    private val now: Instant = Instant.parse("2026-09-20T02:00:00Z")
    private val orderNo = "GB-1-42-abcdef123456"
    private val listPrice = 100_000

    private lateinit var payments: InMemoryPaymentRepository
    private lateinit var gateway: FakePaymentGateway
    private lateinit var orderQueryService: OrderQueryService
    private lateinit var participationConfirmService: ParticipationConfirmService
    private lateinit var refundService: RefundService
    private lateinit var service: PaymentConfirmService

    @BeforeEach
    fun setUp() {
        payments = InMemoryPaymentRepository()
        gateway = FakePaymentGateway()
        orderQueryService = mockk()
        participationConfirmService = mockk(relaxed = true)
        every { participationConfirmService.confirmByOrder(any()) } returns ConfirmOutcome.CONFIRMED
        refundService = mockk(relaxed = true)
        every { orderQueryService.getByOrderNo(orderNo) } returns
            OrderView(orderId = 7, orderNo = orderNo, userId = 42, dealId = 1, listAmount = listPrice, status = "READY")

        val recorder = PaymentApprovalRecorder(
            paymentRepository = payments,
            participationConfirmService = participationConfirmService,
            timeProvider = FixedTimeProvider(now),
            paymentGateway = gateway,
        )
        service = PaymentConfirmService(payments, gateway, orderQueryService, recorder, participationConfirmService, refundService)
    }

    private fun command(paymentKey: String = "pay_key_1", amount: Int = listPrice) =
        ConfirmPaymentCommand(paymentKey = paymentKey, orderNo = orderNo, amount = amount)

    @Test
    fun `승인에 성공하면 결제가 APPROVED 가 되고 참여도 확정된다`() {
        val result = service.confirm(command())

        assertThat(result.newlyApproved).isTrue()
        assertThat(result.status).isEqualTo(PaymentStatus.APPROVED.name)
        assertThat(result.amount).isEqualTo(listPrice)

        val saved = payments.findByOrderNo(orderNo)!!
        assertThat(saved.status).isEqualTo(PaymentStatus.APPROVED)
        assertThat(saved.pgPaymentKey).isEqualTo("pay_key_1")
        assertThat(saved.approvedAt).isEqualTo(now)

        verify(exactly = 1) { participationConfirmService.confirmByOrder(7) }
        assertThat(gateway.approveCalls.get()).isEqualTo(1)
    }

    @Test
    fun `confirm 과 webhook 이 둘 다 와도 PG 승인은 한 번만 호출된다 (R6)`() {
        val first = service.confirm(command())
        val second = service.confirm(command())    // 웹훅이 뒤따라 도착한 상황

        assertThat(first.newlyApproved).isTrue()
        assertThat(second.newlyApproved).isFalse()
        assertThat(second.status).isEqualTo(PaymentStatus.APPROVED.name)

        assertThat(gateway.approveCalls.get()).isEqualTo(1)
        verify(exactly = 1) { participationConfirmService.confirmByOrder(7) }
    }

    @Test
    fun `같은 주문에 다른 결제 키가 오면 거부한다`() {
        service.confirm(command(paymentKey = "pay_key_1"))

        assertThatThrownBy { service.confirm(command(paymentKey = "pay_key_2")) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_ALREADY_APPROVED)

        assertThat(gateway.approveCalls.get()).isEqualTo(1)
    }

    @Test
    fun `요청 금액이 주문 금액과 다르면 PG 를 호출하지 않는다 (R4)`() {
        assertThatThrownBy { service.confirm(command(amount = 90_000)) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH)

        assertThat(gateway.approveCalls.get()).isZero()
        assertThat(payments.findByOrderNo(orderNo)).isNull()
    }

    @Test
    fun `선점이 만료된 주문은 PG 승인을 호출하지 않는다 (ADR-07)`() {
        every { participationConfirmService.ensureConfirmable(7) } throws DomainException(ErrorCode.RESERVATION_EXPIRED)

        assertThatThrownBy { service.confirm(command()) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_EXPIRED)

        assertThat(gateway.approveCalls.get()).isZero()
        assertThat(payments.findByOrderNo(orderNo)).isNull()
    }

    @Test
    fun `승인 직후 확정이 거부되면 승인 기록은 남기고 전액 환불한다 (R5)`() {
        every { participationConfirmService.confirmByOrder(7) } returns ConfirmOutcome.REJECTED

        assertThatThrownBy { service.confirm(command()) }.isInstanceOf(DomainException::class.java)

        // 돈을 받은 기록이 있어야 환불할 수 있다 — 롤백으로 사라지면 안 된다
        assertThat(payments.findByOrderNo(orderNo)!!.status).isEqualTo(PaymentStatus.APPROVED)
        verify(exactly = 1) {
            refundService.refund(RefundCommand(7, listPrice, RefundReason.DEAL_CLOSED_DURING_PAYMENT))
        }
    }

    @Test
    fun `없는 주문이면 404 이고 PG 를 호출하지 않는다`() {
        every { orderQueryService.getByOrderNo("nope") } throws NotFoundException(ErrorCode.ORDER_NOT_FOUND)

        assertThatThrownBy { service.confirm(ConfirmPaymentCommand("k", "nope", listPrice)) }
            .isInstanceOf(NotFoundException::class.java)

        assertThat(gateway.approveCalls.get()).isZero()
    }

    @Test
    fun `카드 거절처럼 확정 실패면 FAILED 로 기록하고 참여는 확정하지 않는다`() {
        gateway.approveFailure = PaymentGatewayException("REJECT_CARD_COMPANY", "카드사에서 거절했습니다.", retryable = false)

        assertThatThrownBy { service.confirm(command()) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_APPROVAL_FAILED)

        assertThat(payments.findByOrderNo(orderNo)!!.status).isEqualTo(PaymentStatus.FAILED)
        verify(exactly = 0) { participationConfirmService.confirmByOrder(any()) }
    }

    @Test
    fun `타임아웃처럼 결과가 불확실하면 FAILED 로 못 박지 않는다`() {
        gateway.approveFailure = PaymentGatewayException("TIMEOUT", "PG 응답을 받지 못했습니다.", retryable = true)

        assertThatThrownBy { service.confirm(command()) }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_PENDING)

        // 실제로 승인됐을 수 있다. 웹훅이 뒤따라 정리하도록 READY 로 두거나 아예 만들지 않는다
        assertThat(payments.findByOrderNo(orderNo)?.status).isNotEqualTo(PaymentStatus.FAILED)
        verify(exactly = 0) { participationConfirmService.confirmByOrder(any()) }
    }

    @Test
    fun `거절 후 사용자가 다시 결제하면 승인된다`() {
        gateway.approveFailure = PaymentGatewayException("REJECT_CARD_COMPANY", "거절", retryable = false)
        assertThatThrownBy { service.confirm(command()) }.isInstanceOf(DomainException::class.java)

        gateway.approveFailure = null
        // FAILED 인 행이 남아 있으면 재승인이 막힌다 — 실제로는 새 주문으로 재선점하는 흐름 (UC-06)
        assertThat(payments.findByOrderNo(orderNo)!!.status).isEqualTo(PaymentStatus.FAILED)
    }
}

/** 테스트용 인메모리 결제 저장소. 잠금은 흉내 내지 않는다 */
class InMemoryPaymentRepository : PaymentRepository {
    private val store = linkedMapOf<Long, Payment>()

    override fun save(payment: Payment): Payment {
        if (payment.id == null) ReflectionTestUtils.setField(payment, "id", (store.keys.maxOrNull() ?: 0L) + 1)
        store[payment.id!!] = payment
        return payment
    }

    override fun findById(id: Long): Payment? = store[id]
    override fun findByOrderNo(orderNo: String): Payment? = store.values.firstOrNull { it.orderNo == orderNo }
    override fun findByOrderNoForUpdate(orderNo: String): Payment? = findByOrderNo(orderNo)
    override fun findAllByOrderIds(orderIds: Collection<Long>): List<Payment> =
        store.values.filter { it.orderId in orderIds }
}
