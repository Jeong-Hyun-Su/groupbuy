package com.groupbuy.payment.infrastructure

import com.groupbuy.payment.domain.Refund
import com.groupbuy.payment.domain.RefundRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface RefundJpaRepository : JpaRepository<Refund, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): Refund?
    fun findAllByPaymentIdIn(paymentIds: Collection<Long>): List<Refund>
}

@Repository
class RefundRepositoryAdapter(
    private val jpa: RefundJpaRepository,
) : RefundRepository {

    override fun save(refund: Refund): Refund = jpa.save(refund)

    override fun findById(id: Long): Refund? = jpa.findById(id).orElse(null)

    override fun findByIdempotencyKey(key: String): Refund? = jpa.findByIdempotencyKey(key)

    override fun findAllByPaymentIds(paymentIds: Collection<Long>): List<Refund> =
        if (paymentIds.isEmpty()) emptyList() else jpa.findAllByPaymentIdIn(paymentIds)
}
