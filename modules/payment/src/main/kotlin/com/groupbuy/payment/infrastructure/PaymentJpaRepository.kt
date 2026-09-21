package com.groupbuy.payment.infrastructure

import com.groupbuy.payment.domain.Payment
import com.groupbuy.payment.domain.PaymentRepository
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

interface PaymentJpaRepository : JpaRepository<Payment, Long> {

    fun findByOrderNo(orderNo: String): Payment?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderNo = :orderNo")
    fun findByOrderNoForUpdate(@Param("orderNo") orderNo: String): Payment?

    fun findAllByOrderIdIn(orderIds: Collection<Long>): List<Payment>
}

@Repository
class PaymentRepositoryAdapter(
    private val jpa: PaymentJpaRepository,
) : PaymentRepository {

    override fun save(payment: Payment): Payment = jpa.save(payment)

    override fun findById(id: Long): Payment? = jpa.findById(id).orElse(null)

    override fun findByOrderNo(orderNo: String): Payment? = jpa.findByOrderNo(orderNo)

    override fun findByOrderNoForUpdate(orderNo: String): Payment? = jpa.findByOrderNoForUpdate(orderNo)

    override fun findAllByOrderIds(orderIds: Collection<Long>): List<Payment> =
        if (orderIds.isEmpty()) emptyList() else jpa.findAllByOrderIdIn(orderIds)
}
