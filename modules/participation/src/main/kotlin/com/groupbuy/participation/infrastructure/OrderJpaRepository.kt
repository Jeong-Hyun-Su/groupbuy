package com.groupbuy.participation.infrastructure

import com.groupbuy.participation.domain.Order
import com.groupbuy.participation.domain.OrderRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByOrderNo(orderNo: String): Order?
}

@Repository
class OrderRepositoryAdapter(
    private val jpa: OrderJpaRepository,
) : OrderRepository {

    override fun save(order: Order): Order = jpa.save(order)

    override fun findById(id: Long): Order? = jpa.findById(id).orElse(null)

    override fun findByOrderNo(orderNo: String): Order? = jpa.findByOrderNo(orderNo)

    override fun findAllByIds(ids: Collection<Long>): List<Order> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids)
}
