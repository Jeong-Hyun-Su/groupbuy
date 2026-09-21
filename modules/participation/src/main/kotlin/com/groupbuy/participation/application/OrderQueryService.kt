package com.groupbuy.participation.application

import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.NotFoundException
import com.groupbuy.participation.domain.Order
import com.groupbuy.participation.domain.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 다른 모듈(payment)이 주문을 읽는 통로. 엔티티를 그대로 넘기지 않고 필요한 값만 준다 */
data class OrderView(
    val orderId: Long,
    val orderNo: String,
    val userId: Long,
    val dealId: Long,
    val listAmount: Int,
    val status: String,
)

@Service
class OrderQueryService(
    private val orderRepository: OrderRepository,
) {

    @Transactional(readOnly = true)
    fun getByOrderNo(orderNo: String): OrderView =
        orderRepository.findByOrderNo(orderNo)?.toView()
            ?: throw NotFoundException(ErrorCode.ORDER_NOT_FOUND)

    private fun Order.toView() = OrderView(
        orderId = id!!,
        orderNo = orderNo,
        userId = userId,
        dealId = dealId,
        listAmount = listAmount,
        status = status.name,
    )
}
