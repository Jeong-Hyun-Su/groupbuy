package com.groupbuy.deal.infrastructure

import com.groupbuy.deal.domain.Deal
import com.groupbuy.deal.domain.DealRepository
import com.groupbuy.deal.domain.DealSort
import com.groupbuy.deal.domain.DealStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

interface DealJpaRepository : JpaRepository<Deal, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Deal d where d.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Deal?

    @Query("select d from Deal d where d.status = :status and d.startAt <= :now order by d.startAt asc")
    fun findByStatusAndStartAtBefore(
        @Param("status") status: DealStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Deal>

    /**
     * 목록 조회. 파라미터가 null 이면 그 조건을 건너뛴다.
     * 티어를 EAGER 로 물고 오므로 `distinct` 가 필요하다 (딜당 티어 N개 → 행 N개).
     */
    @Query(
        """
        select distinct d from Deal d
         where (:statuses is null or d.status in :statuses)
           and lower(d.title) like :keyword
        """,
        countQuery = """
        select count(d) from Deal d
         where (:statuses is null or d.status in :statuses)
           and lower(d.title) like :keyword
        """,
    )
    fun search(
        @Param("statuses") statuses: Collection<DealStatus>?,
        @Param("keyword") keyword: String,
        pageable: Pageable,
    ): List<Deal>

    @Query(
        """
        select count(d) from Deal d
         where (:statuses is null or d.status in :statuses)
           and lower(d.title) like :keyword
        """,
    )
    fun countSearch(
        @Param("statuses") statuses: Collection<DealStatus>?,
        @Param("keyword") keyword: String,
    ): Long

    // Phase 3: 멀티 인스턴스 대비 native 쿼리 + FOR UPDATE SKIP LOCKED 로 교체 (ADR-05)
    @Query("select d from Deal d where d.status in :statuses and d.closeAt <= :now order by d.closeAt asc")
    fun findByStatusInAndCloseAtBefore(
        @Param("statuses") statuses: Collection<DealStatus>,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Deal>
}

@Repository
class DealRepositoryAdapter(
    private val jpa: DealJpaRepository,
) : DealRepository {

    override fun save(deal: Deal): Deal = jpa.save(deal)

    override fun findById(id: Long): Deal? = jpa.findById(id).orElse(null)

    override fun findByIdForUpdate(id: Long): Deal? = jpa.findByIdForUpdate(id)

    override fun findAllByIds(ids: Collection<Long>): List<Deal> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids)

    override fun findDueToOpen(now: Instant, limit: Int): List<Deal> =
        jpa.findByStatusAndStartAtBefore(DealStatus.SCHEDULED, now, Pageable.ofSize(limit))

    override fun findDueToClose(now: Instant, limit: Int): List<Deal> =
        // CLOSING 도 집는다 — 판정·환불이 롤백된 딜은 CLOSING 에 남아 있고, 다음 폴링이 이어서 처리해야 한다
        jpa.findByStatusInAndCloseAtBefore(listOf(DealStatus.OPEN, DealStatus.CLOSING), now, Pageable.ofSize(limit))

    override fun search(
        statuses: Collection<DealStatus>,
        keyword: String?,
        sort: DealSort,
        offset: Int,
        limit: Int,
    ): List<Deal> {
        val pageable = PageRequest.of(offset / limit.coerceAtLeast(1), limit, sort.toOrder())
        return jpa.search(statuses.ifEmpty { null }, keyword.toLikePattern(), pageable)
    }

    override fun countSearch(statuses: Collection<DealStatus>, keyword: String?): Long =
        jpa.countSearch(statuses.ifEmpty { null }, keyword.toLikePattern())

    /**
     * 키워드를 like 패턴으로 바꾼다. 키워드가 없으면 전체 매칭('%')이다.
     *
     * null 을 그대로 바인딩하면 PostgreSQL 이 타입을 못 정해 `text ~~ bytea` 로 터진다.
     * 그래서 `:keyword is null` 분기를 쓰지 않고 항상 non-null 패턴을 넘긴다.
     * 사용자 입력의 %, _ 는 와일드카드로 동작하지 않게 이스케이프한다.
     */
    private fun String?.toLikePattern(): String {
        val k = this?.takeIf { it.isNotBlank() } ?: return "%"
        val escaped = k.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    private fun DealSort.toOrder(): Sort = when (this) {
        DealSort.CLOSING_SOON -> Sort.by(Sort.Direction.ASC, "closeAt")
        DealSort.LATEST -> Sort.by(Sort.Direction.DESC, "id")
    }
}
