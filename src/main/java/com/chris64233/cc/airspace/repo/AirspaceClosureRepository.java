package com.chris64233.cc.airspace.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.AirspaceClosure;

public interface AirspaceClosureRepository extends JpaRepository<AirspaceClosure, Long> {

    /**
     * 抓取关闭事件详情（含区域航段与航段本体），按关联表主键排序。
     */
    @Query("""
            select distinct c from AirspaceClosure c
            left join fetch c.segments cs
            left join fetch cs.segment
            where c.closureNo = :closureNo
            """)
    Optional<AirspaceClosure> findDetailByClosureNo(@Param("closureNo") String closureNo);

    Optional<AirspaceClosure> findByClosureNo(String closureNo);

    /**
     * 按关闭事件号取主键（标量），用于加锁前探测与存在性判断，不污染一级缓存。
     */
    @Query("select c.id from AirspaceClosure c where c.closureNo = :closureNo")
    Optional<Long> findIdByClosureNo(@Param("closureNo") String closureNo);

    /**
     * 对关闭事件行加写锁：修订、取消与改道确认都先取该锁串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AirspaceClosure c where c.closureNo = :closureNo")
    Optional<AirspaceClosure> findByClosureNoForUpdate(@Param("closureNo") String closureNo);

    /**
     * 按主键升序批量加写锁，所有事务同一顺序加锁，避免死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AirspaceClosure c where c.id in :ids order by c.id")
    List<AirspaceClosure> findAllByIdForUpdateOrderById(@Param("ids") List<Long> ids);

    /**
     * 查询覆盖给定航段集合、且与 [start, end) 时间窗重叠的有效关闭事件（不加锁）。
     * 左闭右开：端点相接不算重叠。
     */
    @Query("""
            select distinct c from AirspaceClosure c
            join c.segments cs
            where c.status = com.chris64233.cc.airspace.domain.ClosureStatus.ACTIVE
              and cs.segment.id in :segmentIds
              and c.startTime < :endTime
              and c.endTime > :startTime
            """)
    List<AirspaceClosure> findActiveForSegmentsAndWindow(@Param("segmentIds") List<Long> segmentIds,
                                                         @Param("startTime") Instant startTime,
                                                         @Param("endTime") Instant endTime);

    /**
     * 与 {@link #findActiveForSegmentsAndWindow} 相同的筛选条件，但对关闭事件行加写锁。
     * 提交 / 改道容量事务中使用，与取消、修订关闭事件互斥。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct c from AirspaceClosure c
            join c.segments cs
            where c.status = com.chris64233.cc.airspace.domain.ClosureStatus.ACTIVE
              and cs.segment.id in :segmentIds
              and c.startTime < :endTime
              and c.endTime > :startTime
            order by c.id
            """)
    List<AirspaceClosure> findActiveForSegmentsAndWindowForUpdate(
            @Param("segmentIds") List<Long> segmentIds,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    List<AirspaceClosure> findAllByOrderById();
}
