package com.groupbuy.payment.domain

/** 도메인 포트. 구현은 infrastructure. */
interface PaymentRepository {
    fun save(payment: Payment): Payment
    fun findById(id: Long): Payment?
    fun findByOrderNo(orderNo: String): Payment?

    /**
     * 행 잠금 조회. confirm 과 webhook 이 동시에 같은 주문을 승인하려는 경쟁을 직렬화한다.
     * 행이 없으면 null — 이 경우 insert 경쟁은 `order_no` UNIQUE 제약이 막는다.
     */
    fun findByOrderNoForUpdate(orderNo: String): Payment?

    fun findAllByOrderIds(orderIds: Collection<Long>): List<Payment>
}
