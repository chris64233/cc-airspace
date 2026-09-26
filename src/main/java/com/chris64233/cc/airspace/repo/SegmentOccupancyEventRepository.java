package com.chris64233.cc.airspace.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.SegmentOccupancyEvent;

public interface SegmentOccupancyEventRepository extends JpaRepository<SegmentOccupancyEvent, Long> {

    /**
     * 某许可的逐航段占用事件，按发生顺序（主键）升序。
     */
    @Query("""
            select e from SegmentOccupancyEvent e
            join fetch e.segment
            where e.clearance.externalNo = :externalNo
            order by e.id
            """)
    List<SegmentOccupancyEvent> findByClearanceExternalNoOrderById(@Param("externalNo") String externalNo);
}
