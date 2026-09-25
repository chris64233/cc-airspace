package com.chris64233.cc.airspace.repo;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.SegmentReservation;

public interface SegmentReservationRepository extends JpaRepository<SegmentReservation, Long> {

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
}
