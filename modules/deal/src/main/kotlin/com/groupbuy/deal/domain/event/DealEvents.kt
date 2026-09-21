package com.groupbuy.deal.domain.event

import com.groupbuy.common.event.DomainEvent
import com.groupbuy.deal.domain.CloseResult
import java.time.Instant
import java.util.UUID

data class DealOpened(
    val dealId: Long,
    override val occurredAt: Instant,
    override val eventId: UUID = UUID.randomUUID(),
) : DomainEvent {
    override val eventType: String = "deal.opened"
    override val aggregateType: String = "DEAL"
    override val aggregateId: String = dealId.toString()
}

data class DealClosed(
    val result: CloseResult,
    override val occurredAt: Instant,
    override val eventId: UUID = UUID.randomUUID(),
) : DomainEvent {
    override val eventType: String = "deal.closed"
    override val aggregateType: String = "DEAL"
    override val aggregateId: String = result.dealId.toString()
}
