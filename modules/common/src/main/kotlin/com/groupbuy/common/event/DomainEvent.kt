package com.groupbuy.common.event

import java.time.Instant
import java.util.UUID

/**
 * 설계서 8.2 이벤트 envelope 의 도메인 측 표현.
 * Kafka 로 나갈 때 envelope 로 감싼다 (Phase 3).
 */
interface DomainEvent {
    val eventId: UUID
    val eventType: String
    val aggregateType: String
    val aggregateId: String
    val occurredAt: Instant
}
