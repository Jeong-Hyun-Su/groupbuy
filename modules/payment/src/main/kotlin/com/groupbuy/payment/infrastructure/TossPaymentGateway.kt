package com.groupbuy.payment.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.groupbuy.payment.domain.PaymentGateway
import com.groupbuy.payment.domain.PaymentGatewayException
import com.groupbuy.payment.domain.WebhookSecretProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 토스페이먼츠 어댑터 (결제 승인 v1).
 *
 * 인증: 시크릿 키 + ':' 을 Base64 로 인코딩한 Basic 인증.
 * 오류 분류가 이 어댑터의 핵심 책임이다 — 타임아웃·5xx 는 재시도 가능(결과 불확실),
 * 카드 거절 같은 4xx 는 재시도 불가(확정 실패)로 나눠 상위가 판단할 수 있게 한다.
 */
@Component
class TossPaymentGateway(
    private val properties: PaymentProperties,
    private val restClient: RestClient,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun providerName(): String = properties.provider

    override fun approve(request: PaymentGateway.ApproveRequest): PaymentGateway.ApproveResult {
        val body = mapOf(
            "paymentKey" to request.paymentKey,
            "orderId" to request.orderNo,
            "amount" to request.amount,
        )
        val response = post("/v1/payments/confirm", body, idempotencyKey = null)
        return response.toApproveResult()
    }

    override fun cancel(request: PaymentGateway.CancelRequest): PaymentGateway.CancelResult {
        val body = mapOf(
            "cancelReason" to request.reason,
            "cancelAmount" to request.cancelAmount,
        )
        val response = post(
            "/v1/payments/${request.paymentKey}/cancel",
            body,
            idempotencyKey = request.idempotencyKey,
        )
        val lastCancel = response.path("cancels").lastOrNull()
        return PaymentGateway.CancelResult(
            cancelKey = lastCancel?.path("transactionKey")?.asText() ?: request.idempotencyKey,
            canceledAmount = lastCancel?.path("cancelAmount")?.asInt() ?: request.cancelAmount,
        )
    }

    override fun findByOrderNo(orderNo: String): PaymentGateway.ApproveResult? =
        try {
            restClient.get()
                .uri("/v1/payments/orders/{orderNo}", orderNo)
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .retrieve()
                .onStatus({ it.is4xxClientError }) { _, _ -> /* 없으면 null */ }
                .body(JsonNode::class.java)
                ?.takeIf { it.path("status").asText() == "DONE" }
                ?.toApproveResult()
        } catch (e: ResourceAccessException) {
            throw PaymentGatewayException("TIMEOUT", "PG 조회에 실패했습니다.", retryable = true, cause = e)
        }

    private fun post(path: String, body: Map<String, Any>, idempotencyKey: String?): JsonNode {
        val result: JsonNode?
        try {
            result = restClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
                .body(body)
                .exchange { _, response ->
                    val json = runCatching { response.bodyTo(JsonNode::class.java) }.getOrNull()
                    if (response.statusCode.is2xxSuccessful) {
                        json
                    } else {
                        throw toException(response.statusCode, json)
                    }
                }
        } catch (e: ResourceAccessException) {
            // 연결 실패·타임아웃 — 승인됐는지 알 수 없다
            throw PaymentGatewayException("TIMEOUT", "PG 응답을 받지 못했습니다.", retryable = true, cause = e)
        }
        return result ?: throw PaymentGatewayException("EMPTY_BODY", "PG 응답이 비어 있습니다.", retryable = true)
    }

    /**
     * 토스 오류 응답을 도메인 예외로 바꾼다.
     * 5xx 와 일부 코드는 결과가 불확실해 재시도 가능으로 본다. 나머지 4xx 는 확정 실패다.
     */
    private fun toException(status: HttpStatusCode, json: JsonNode?): PaymentGatewayException {
        val code = json?.path("code")?.asText().orEmpty().ifBlank { "UNKNOWN" }
        val message = json?.path("message")?.asText().orEmpty().ifBlank { "결제 승인에 실패했습니다." }
        val retryable = status.is5xxServerError || code in RETRYABLE_CODES
        log.info("toss error: status={} code={} retryable={}", status.value(), code, retryable)
        return PaymentGatewayException(code, message, retryable)
    }

    private fun basicAuth(): String {
        val encoded = Base64.getEncoder().encodeToString("${properties.toss.secretKey}:".toByteArray(StandardCharsets.UTF_8))
        return "Basic $encoded"
    }

    private fun JsonNode.toApproveResult() = PaymentGateway.ApproveResult(
        paymentKey = path("paymentKey").asText(),
        approvedAmount = path("totalAmount").asInt(),
        approvedAtIso = path("approvedAt").asText(),
    )

    companion object {
        /** 결과가 불확실해 재시도해도 되는 토스 오류 코드 */
        private val RETRYABLE_CODES = setOf(
            "FAILED_INTERNAL_SYSTEM_PROCESSING",
            "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",
            "PROVIDER_ERROR",
            "UNKNOWN_PAYMENT_ERROR",
        )
    }
}

@Configuration
class PaymentGatewayConfig {

    /** 웹훅 시크릿을 application 레이어에 포트로 넘긴다 */
    @Bean
    fun webhookSecretProvider(properties: PaymentProperties): WebhookSecretProvider =
        WebhookSecretProvider { properties.toss.webhookSecret }

    @Bean
    fun tossRestClient(properties: PaymentProperties, customizers: List<RestClientCustomizer>): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.toss.connectTimeout)
            setReadTimeout(properties.toss.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(properties.toss.baseUrl)
            .requestFactory(factory)
            .also { builder -> customizers.forEach { it.customize(builder) } }
            .build()
    }
}
