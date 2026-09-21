package com.groupbuy.payment.domain.event

import com.groupbuy.common.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** 설계서 8.1 `payment.events` 토픽. 파티션 키는 orderId */
data class PaymentApproved(
    val paymentId: Long,
    val orderId: Long,
    val orderNo: String,
    val amount: Int,
    override val occurredAt: Instant,
    override val eventId: UUID = UUID.randomUUID(),
) : DomainEvent {
    override val eventType: String = "payment.approved"
    override val aggregateType: String = "ORDER"
    override val aggregateId: String = orderId.toString()
}
