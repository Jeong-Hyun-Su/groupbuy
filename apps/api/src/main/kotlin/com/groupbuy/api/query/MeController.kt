package com.groupbuy.api.query

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/me")
class MeController(
    private val myParticipationsQuery: MyParticipationsQuery,
) {

    /** UC-11 내 참여 목록. 인증 도입 전까지 X-User-Id 헤더 */
    @GetMapping("/participations")
    fun participations(@RequestHeader("X-User-Id") userId: Long): List<MyParticipationResponse> =
        myParticipationsQuery.list(userId)
}
