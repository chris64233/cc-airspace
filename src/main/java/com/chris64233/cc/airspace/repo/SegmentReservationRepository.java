package com.chris64233.cc.airspace.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.SegmentReservation;

public interface SegmentReservationRepository extends JpaRepository<SegmentReservation, Long> {

    /**
     * 某许可当前航线占用的航段主键（标量投影，不污染一级缓存），
     * 供改道 / 取消事务在持有许可锁之前探测需要加锁的航段集合。
     */
    @Query("""
            select r.segment.id from SegmentReservation r
            where r.clearance.id = :clearanceId
            order by r.segmentOrder
            """)
    List<Long> findSegmentIdsByClearanceId(@Param("clearanceId") Long clearanceId);

    /**
     * 统计某航段在 [start, end) 时间区间内与其重叠的有效许可占用数量。
     * 左闭右开语义下，start = existing.end 或 end = existing.start 不算重叠。
     */
    @Query("""
            select count(r) from SegmentReservation r
            where r.segment.id = :segmentId
              and r.clearance.status = com.chris64233.cc.airspace.domain.ClearanceStatus.ACTIVE
              and r.clearance.startTime < :endTime
              and r.clearance.endTime > :startTime
            """)
    long countActiveOverlaps(@Param("segmentId") Long segmentId,
                             @Param("startTime") Instant startTime,
                             @Param("endTime") Instant endTime);

    /**
     * 与 {@link #countActiveOverlaps} 相同，但排除指定许可自身的占用。
     * 改道容量判定时使用：被保留的自有航段不应占用新容量。
     */
    @Query("""
            select count(r) from SegmentReservation r
            where r.segment.id = :segmentId
              and r.clearance.status = com.chris64233.cc.airspace.domain.ClearanceStatus.ACTIVE
              and r.clearance.id <> :excludeClearanceId
              and r.clearance.startTime < :endTime
              and r.clearance.endTime > :startTime
            """)
    long countActiveOverlapsExcluding(@Param("segmentId") Long segmentId,
                                      @Param("startTime") Instant startTime,
                                      @Param("endTime") Instant endTime,
                                      @Param("excludeClearanceId") Long excludeClearanceId);
}
