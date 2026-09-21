package com.groupbuy.participation.application

import com.groupbuy.deal.domain.Deal
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.deal.domain.DealSort
import com.groupbuy.deal.domain.DealStatus
import com.groupbuy.participation.domain.Order
import com.groupbuy.participation.domain.OrderRepository
import com.groupbuy.participation.domain.Participation
import com.groupbuy.participation.domain.ParticipationRepository
import com.groupbuy.participation.domain.ParticipationStatus
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

/** 단위 테스트용 인메모리 어댑터. 잠금은 흉내 내지 않는다 — 동시성은 통합 테스트(apps/api) 에서 검증한다 */
class InMemoryDealRepository : DealRepository {
    private val store = linkedMapOf<Long, Deal>()
    var lockedIds = mutableListOf<Long>()

    override fun save(deal: Deal): Deal {
        if (deal.id == null) ReflectionTestUtils.setField(deal, "id", (store.keys.maxOrNull() ?: 0L) + 1)
        store[deal.id!!] = deal
        return deal
    }

    override fun findById(id: Long): Deal? = store[id]
    override fun findByIdForUpdate(id: Long): Deal? = store[id]?.also { lockedIds += id }
    override fun findAllByIds(ids: Collection<Long>): List<Deal> = ids.mapNotNull { store[it] }
    override fun findDueToOpen(now: Instant, limit: Int): List<Deal> = emptyList()
    override fun findDueToClose(now: Instant, limit: Int): List<Deal> = emptyList()

    override fun search(
        statuses: Collection<DealStatus>,
        keyword: String?,
        sort: DealSort,
        offset: Int,
        limit: Int,
    ): List<Deal> = store.values
        .filter { statuses.isEmpty() || it.status in statuses }
        .filter { keyword == null || it.title.contains(keyword, ignoreCase = true) }
        .drop(offset)
        .take(limit)

    override fun countSearch(statuses: Collection<DealStatus>, keyword: String?): Long = store.values
        .count { (statuses.isEmpty() || it.status in statuses) && (keyword == null || it.title.contains(keyword, ignoreCase = true)) }
        .toLong()
}

class InMemoryParticipationRepository : ParticipationRepository {
    private val store = linkedMapOf<Long, Participation>()

    override fun save(participation: Participation): Participation {
        if (participation.id == null) ReflectionTestUtils.setField(participation, "id", (store.keys.maxOrNull() ?: 0L) + 1)
        store[participation.id!!] = participation
        return participation
    }

    override fun findById(id: Long): Participation? = store[id]
    override fun findByDealIdAndUserId(dealId: Long, userId: Long): Participation? =
        store.values.firstOrNull { it.dealId == dealId && it.userId == userId }
    override fun countOccupying(dealId: Long): Int = store.values.count { it.dealId == dealId && it.occupiesSlot }
    override fun findAllByUserId(userId: Long): List<Participation> = store.values.filter { it.userId == userId }

    override fun findConfirmed(dealId: Long): List<Participation> =
        store.values.filter { it.dealId == dealId && it.status == ParticipationStatus.CONFIRMED }

    override fun findExpiredReservations(now: Instant, limit: Int): List<Participation> =
        store.values.filter { it.isReservationExpired(now) }.take(limit)

    fun all(): List<Participation> = store.values.toList()
}

class InMemoryOrderRepository : OrderRepository {
    private val store = linkedMapOf<Long, Order>()

    override fun save(order: Order): Order {
        if (order.id == null) ReflectionTestUtils.setField(order, "id", (store.keys.maxOrNull() ?: 0L) + 1)
        store[order.id!!] = order
        return order
    }

    override fun findById(id: Long): Order? = store[id]
    override fun findByOrderNo(orderNo: String): Order? = store.values.firstOrNull { it.orderNo == orderNo }
    override fun findAllByIds(ids: Collection<Long>): List<Order> = ids.mapNotNull { store[it] }

    fun all(): List<Order> = store.values.toList()
}
