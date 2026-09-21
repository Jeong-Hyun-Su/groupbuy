package com.groupbuy.participation.domain.event

import com.groupbuy.common.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** 설계서 8.1 `participation.events` 토픽. 파티션 키는 dealId — 같은 딜의 이벤트는 순서를 지킨다 */
data class ParticipationReserved(
    val dealId: Long,
    val userId: Long,
    override val occurredAt: Instant,
    override val eventId: UUID = UUID.randomUUID(),
) : DomainEvent {
    override val eventType: String = "participation.reserved"
    override val aggregateType: String = "DEAL"
    override val aggregateId: String = dealId.toString()
}

data class ParticipationConfirmed(
    val dealId: Long,
    val userId: Long,
    val participationId: Long,
    override val occurredAt: Instant,
    override val eventId: UUID = UUID.randomUUID(),
) : DomainEvent {
    override val eventType: String = "participation.confirmed"
    override val aggregateType: String = "DEAL"
    override val aggregateId: String = dealId.toString()
}

data class ParticipationExpired(
    val dealId: Long,
    val userId: Long,
    val participationId: Long,
    override val occurredAt: Instant,
    override val eventId: UUID = UUID.randomUUID(),
) : DomainEvent {
    override val eventType: String = "participation.expired"
    override val aggregateType: String = "DEAL"
    override val aggregateId: String = dealId.toString()
}
