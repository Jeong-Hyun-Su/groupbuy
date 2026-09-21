package com.groupbuy.api.web

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 설계서 9.1 에러 응답 표준 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val traceId: String?,
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun handleDomain(e: DomainException): ResponseEntity<ErrorResponse> {
        log.info("domain error: {} - {}", e.errorCode, e.message)
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ErrorResponse(e.errorCode.name, e.message ?: e.errorCode.defaultMessage, traceId()))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ErrorCode.INVALID_REQUEST.name, message, traceId()))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unexpected error", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "일시적인 오류가 발생했습니다.", traceId()))
    }

    private fun traceId(): String? = MDC.get("traceId")
}
