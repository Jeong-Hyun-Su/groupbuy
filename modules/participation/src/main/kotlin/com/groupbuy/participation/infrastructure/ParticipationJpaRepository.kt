package com.groupbuy.participation.infrastructure

import com.groupbuy.participation.domain.Participation
import com.groupbuy.participation.domain.ParticipationRepository
import com.groupbuy.participation.domain.ParticipationStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

interface ParticipationJpaRepository : JpaRepository<Participation, Long> {
    fun findByDealIdAndUserId(dealId: Long, userId: Long): Participation?
    fun countByDealIdAndStatusIn(dealId: Long, statuses: Collection<ParticipationStatus>): Long
    fun findAllByUserIdOrderByIdDesc(userId: Long): List<Participation>
    fun findAllByDealIdAndStatus(dealId: Long, status: ParticipationStatus): List<Participation>

    @Query(
        """
        select p from Participation p
         where p.status = :status and p.reservationExpiresAt <= :now
         order by p.reservationExpiresAt asc
        """,
    )
    fun findExpiredReservations(
        @Param("status") status: ParticipationStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Participation>
}

@Repository
class ParticipationRepositoryAdapter(
    private val jpa: ParticipationJpaRepository,
) : ParticipationRepository {

    override fun save(participation: Participation): Participation = jpa.save(participation)

    override fun findById(id: Long): Participation? = jpa.findById(id).orElse(null)

    override fun findByDealIdAndUserId(dealId: Long, userId: Long): Participation? =
        jpa.findByDealIdAndUserId(dealId, userId)

    override fun countOccupying(dealId: Long): Int =
        jpa.countByDealIdAndStatusIn(dealId, OCCUPYING).toInt()

    override fun findAllByUserId(userId: Long): List<Participation> = jpa.findAllByUserIdOrderByIdDesc(userId)

    override fun findConfirmed(dealId: Long): List<Participation> =
        jpa.findAllByDealIdAndStatus(dealId, ParticipationStatus.CONFIRMED)

    override fun findExpiredReservations(now: Instant, limit: Int): List<Participation> =
        jpa.findExpiredReservations(ParticipationStatus.RESERVED, now, Pageable.ofSize(limit))

    companion object {
        private val OCCUPYING = ParticipationStatus.entries.filter { it.occupiesSlot }
    }
}
