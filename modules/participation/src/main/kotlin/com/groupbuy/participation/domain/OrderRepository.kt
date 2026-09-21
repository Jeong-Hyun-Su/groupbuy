package com.groupbuy.participation.domain

/** 도메인 포트. 구현은 infrastructure. */
interface OrderRepository {
    fun save(order: Order): Order
    fun findById(id: Long): Order?
    fun findByOrderNo(orderNo: String): Order?
    fun findAllByIds(ids: Collection<Long>): List<Order>
}
