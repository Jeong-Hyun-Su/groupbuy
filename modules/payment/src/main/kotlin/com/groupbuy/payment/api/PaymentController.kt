package com.groupbuy.payment.api

import com.groupbuy.payment.application.ConfirmPaymentCommand
import com.groupbuy.payment.application.PaymentConfirmService
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 결제 API. 설계서 9.2.
 *
 * `/confirm` 은 PG 성공 리다이렉트 후 클라이언트가 호출하고, `/webhook` 은 PG 서버가 호출한다.
 * 둘 중 먼저 온 쪽이 승인하고 나머지는 멱등하게 skip 된다 (R6).
 */
@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentConfirmService: PaymentConfirmService,
) {

    data class ConfirmRequest(
        @field:NotBlank val paymentKey: String,
        @field:NotBlank val orderId: String,   // 토스 규격상 이름이 orderId 다. 우리의 orderNo
        @field:Min(1) val amount: Int,
    )

    data class ConfirmResponse(
        val orderNo: String,
        val amount: Int,
        val status: String,
    )

    @PostMapping("/confirm")
    fun confirm(@Valid @RequestBody request: ConfirmRequest): ConfirmResponse {
        val result = paymentConfirmService.confirm(
            ConfirmPaymentCommand(
                paymentKey = request.paymentKey,
                orderNo = request.orderId,
                amount = request.amount,
            ),
        )
        return ConfirmResponse(result.orderNo, result.amount, result.status)
    }
}
