package com.chris64233.cc.airspace.repo;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.AirspaceClosureSegment;

public interface AirspaceClosureSegmentRepository extends JpaRepository<AirspaceClosureSegment, Long> {

    /**
     * 统计某航段在给定时间窗内重叠的生效中关闭事件数量。
     * 大于 0 表示该航段在该时段处于关闭区域，新航线不得使用。
     */
    @Query("""
            select count(distinct cl) from AirspaceClosureSegment cs
            join cs.closure cl
            where cs.segment.id = :segmentId
              and cl.status = com.chris64233.cc.airspace.domain.ClosureStatus.ACTIVE
              and cl.startTime < :endTime
              and cl.endTime > :startTime
            """)
    long countActiveClosures(@Param("segmentId") Long segmentId,
                             @Param("startTime") Instant startTime,
                             @Param("endTime") Instant endTime);

    /**
     * 先于成员替换物理删除某关闭事件的全部航段成员并立即 flush，
     * 保证新成员写入时 (closure_id, segment_id) 唯一约束上已无旧行。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from AirspaceClosureSegment cs where cs.closure.id = :closureId")
    void deleteAllByClosureId(@Param("closureId") Long closureId);
}
