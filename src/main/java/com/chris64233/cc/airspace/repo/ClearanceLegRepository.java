package com.chris64233.cc.airspace.repo;

import com.chris64233.cc.airspace.domain.ClearanceLeg;
import com.chris64233.cc.airspace.domain.ClearanceStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClearanceLegRepository extends JpaRepository<ClearanceLeg, Long> {

    @Query("""
            select count(distinct leg.clearance.id)
            from ClearanceLeg leg
            where leg.segmentCode = :segmentCode
              and leg.clearance.status = :status
              and leg.clearance.startTime < :endTime
              and leg.clearance.endTime > :startTime
            """)
    long countApprovedOverlaps(@Param("segmentCode") String segmentCode,
                               @Param("status") ClearanceStatus status,
                               @Param("startTime") Instant startTime,
                               @Param("endTime") Instant endTime);
}
