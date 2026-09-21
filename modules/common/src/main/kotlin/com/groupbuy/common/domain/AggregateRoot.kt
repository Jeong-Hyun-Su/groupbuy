package com.groupbuy.common.domain

import com.groupbuy.common.event.DomainEvent
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Transient

/**
 * 도메인 이벤트를 수집하는 애그리거트 루트.
 * Phase 1: 이벤트는 수집만 한다. Phase 3에서 Outbox 로 발행한다.
 */
@MappedSuperclass
abstract class AggregateRoot {

    @Transient
    private val pendingEvents: MutableList<DomainEvent> = mutableListOf()

    protected fun registerEvent(event: DomainEvent) {
        pendingEvents.add(event)
    }

    fun pollEvents(): List<DomainEvent> {
        val copy = pendingEvents.toList()
        pendingEvents.clear()
        return copy
    }
}
