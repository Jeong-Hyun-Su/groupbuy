package com.groupbuy.payment.domain

/** 도메인 포트. 구현은 infrastructure. */
interface RefundRepository {
    fun save(refund: Refund): Refund
    fun findById(id: Long): Refund?
    fun findByIdempotencyKey(key: String): Refund?
    fun findAllByPaymentIds(paymentIds: Collection<Long>): List<Refund>
}
