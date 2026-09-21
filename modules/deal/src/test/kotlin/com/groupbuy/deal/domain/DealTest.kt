package com.groupbuy.deal.domain

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.InvalidStateTransitionException
import com.groupbuy.deal.domain.event.DealClosed
import com.groupbuy.deal.domain.event.DealOpened
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DealTest {

    @Nested
    inner class `딜 생성 검증` {

        @Test
        fun `정상 생성 시 DRAFT 상태이고 티어는 오름차순이다`() {
            val deal = DealFixture.deal()

            assertThat(deal.status).isEqualTo(DealStatus.DRAFT)
            assertThat(deal.tiers.map { it.minCount }).containsExactly(10, 30, 50)
        }

        @Test
        fun `정원이 최소 인원보다 작으면 실패한다`() {
            assertThatThrownBy { DealFixture.deal(minParticipants = 10, capacity = 9) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.DEAL_INVALID_CAPACITY)
        }

        @Test
        fun `마감 시각이 시작 시각 이후가 아니면 실패한다`() {
            assertThatThrownBy { DealFixture.deal(startAt = DealFixture.CLOSE, closeAt = DealFixture.START) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.DEAL_INVALID_PERIOD)
        }

        @Test
        fun `잘못된 티어 구성이면 실패한다`() {
            assertThatThrownBy { DealFixture.deal(tiers = listOf(DiscountPolicy.TierRule(10, 30), DiscountPolicy.TierRule(30, 10))) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.DEAL_INVALID_TIERS)
        }
    }

    @Nested
    inner class `상태 전이` {

        @Test
        fun `DRAFT → SCHEDULED → OPEN 순서로 전이되고 오픈 이벤트가 등록된다`() {
            val deal = DealFixture.deal()

            deal.schedule()
            assertThat(deal.status).isEqualTo(DealStatus.SCHEDULED)

            deal.open(DealFixture.START)
            assertThat(deal.status).isEqualTo(DealStatus.OPEN)
            assertThat(deal.isOpen).isTrue()
            assertThat(deal.pollEvents()).singleElement().isInstanceOf(DealOpened::class.java)
        }

        @Test
        fun `시작 시각 전에는 오픈할 수 없다`() {
            val deal = DealFixture.deal().apply { schedule() }

            assertThatThrownBy { deal.open(DealFixture.START.minusSeconds(1)) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `DRAFT 에서 바로 OPEN 으로 갈 수 없다`() {
            assertThatThrownBy { DealFixture.deal().open(DealFixture.START) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `마감 시각 전에는 마감을 시작할 수 없다`() {
            val deal = DealFixture.openDeal()

            assertThatThrownBy { deal.beginClosing(DealFixture.CLOSE.minusSeconds(1)) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `마감 시작은 한 번만 가능하다 (R8)`() {
            val deal = DealFixture.closingDeal()

            assertThatThrownBy { deal.beginClosing(DealFixture.CLOSE) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `오픈된 딜은 취소할 수 없다`() {
            assertThatThrownBy { DealFixture.openDeal().cancel() }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `무산된 딜은 정산 완료로 전이할 수 없다`() {
            val deal = DealFixture.closingDeal().apply { finishClosing(confirmedCount = 3, now = DealFixture.CLOSE) }

            assertThatThrownBy { deal.markSettled() }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }
    }

    @Nested
    inner class `마감 판정` {

        @Test
        fun `최소 인원 이상이면 성사되고 최종 인원의 티어로 할인율이 확정된다 (R3)`() {
            val deal = DealFixture.closingDeal()

            val result = deal.finishClosing(confirmedCount = 47, now = DealFixture.CLOSE)

            assertThat(result.succeeded).isTrue()
            assertThat(result.finalDiscountRate).isEqualTo(20)
            assertThat(result.finalPrice).isEqualTo(80_000)
            assertThat(deal.status).isEqualTo(DealStatus.SUCCEEDED)
            assertThat(deal.finalParticipantCount).isEqualTo(47)
            assertThat(deal.finalPrice()).isEqualTo(80_000)
            assertThat(deal.pollEvents().last()).isInstanceOf(DealClosed::class.java)
        }

        @Test
        fun `최소 인원 미달이면 무산되고 할인율 0, 확정 금액은 정가다 (R5 전액 환불 기준)`() {
            val deal = DealFixture.closingDeal()

            val result = deal.finishClosing(confirmedCount = 7, now = DealFixture.CLOSE)

            assertThat(result.succeeded).isFalse()
            assertThat(result.finalDiscountRate).isEqualTo(0)
            assertThat(result.finalPrice).isEqualTo(100_000)
            assertThat(deal.status).isEqualTo(DealStatus.FAILED)
        }

        @Test
        fun `정확히 최소 인원이면 성사된다`() {
            val result = DealFixture.closingDeal().finishClosing(confirmedCount = 10, now = DealFixture.CLOSE)

            assertThat(result.succeeded).isTrue()
            assertThat(result.finalDiscountRate).isEqualTo(10)
        }

        @Test
        fun `확정 인원이 정원을 넘으면 판정을 거부한다 (R1)`() {
            assertThatThrownBy { DealFixture.closingDeal().finishClosing(confirmedCount = 201, now = DealFixture.CLOSE) }
                .isInstanceOf(DomainException::class.java)
        }

        @Test
        fun `CLOSING 이 아닌 상태에서는 판정할 수 없다`() {
            assertThatThrownBy { DealFixture.openDeal().finishClosing(confirmedCount = 47, now = DealFixture.CLOSE) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `마감 전에는 확정 금액을 조회할 수 없다`() {
            assertThatThrownBy { DealFixture.openDeal().finalPrice() }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }
    }

    @Nested
    inner class `참여 접수 판정` {

        @Test
        fun `OPEN 이고 마감 전이면 참여를 받는다`() {
            val deal = DealFixture.openDeal()

            assertThat(deal.acceptsParticipation(DealFixture.CLOSE.minusSeconds(1))).isTrue()
        }

        @Test
        fun `마감 시각에 도달하면 아직 OPEN 이어도 참여를 받지 않는다`() {
            val deal = DealFixture.openDeal()

            assertThat(deal.acceptsParticipation(DealFixture.CLOSE)).isFalse()
            assertThatThrownBy { deal.ensureAcceptsParticipation(DealFixture.CLOSE) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.DEAL_NOT_OPEN)
        }

        @Test
        fun `OPEN 이 아니면 참여를 받지 않는다`() {
            val scheduled = DealFixture.deal().apply { schedule() }

            assertThatThrownBy { scheduled.ensureAcceptsParticipation(DealFixture.START) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.DEAL_NOT_OPEN)
        }
    }

    @Nested
    inner class `예상 할인율` {

        @Test
        fun `현재 인원 기준 예상 할인율과 가격, 다음 티어를 계산한다`() {
            val deal = DealFixture.openDeal()

            assertThat(deal.projectedDiscountRate(28)).isEqualTo(10)
            assertThat(deal.projectedPrice(28)).isEqualTo(90_000)
            assertThat(deal.nextTierAfter(28)).isEqualTo(DiscountPolicy.TierRule(30, 20))
        }
    }
}
