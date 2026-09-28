package com.chris64233.cc.airspace.repo;

import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.ClosureImpact;
import com.chris64233.cc.airspace.domain.ClosureImpactStatus;

public interface ClosureImpactRepository extends JpaRepository<ClosureImpact, Long> {

    /**
     * 抓取某关闭事件的全部影响标记（含航班、航段顺序），按航段顺序排序。
     */
    @Query("""
            select distinct i from ClosureImpact i
            join fetch i.clearance c
            join fetch i.closure cl
            where i.closure.eventNo = :eventNo
            order by c.externalNo, i.segmentOrder
            """)
    List<ClosureImpact> findDetailByEventNo(@Param("eventNo") String eventNo);

    /**
     * 某关闭事件下指定状态的标记（用于创建时识别尚未处理的航班、取消时批量解除）。
     */
    List<ClosureImpact> findByClosureEventNoAndStatus(String eventNo, ClosureImpactStatus status);

    /**
     * 某航班的全部影响标记（按关闭事件与航段顺序）。
     */
    @Query("""
            select i from ClosureImpact i
            join fetch i.closure
            join fetch i.clearance
            where i.clearance.externalNo = :externalNo
            order by i.id
            """)
    List<ClosureImpact> findDetailByClearanceExternalNo(@Param("externalNo") String externalNo);

    /**
     * 对某航班全部待处理标记加写锁（许可锁之后使用，与位置上报 / 关闭取消串行化）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from ClosureImpact i
            join fetch i.closure cl
            where i.clearance.id = :clearanceId
              and i.status = com.chris64233.cc.airspace.domain.ClosureImpactStatus.PENDING
            order by i.id
            """)
    List<ClosureImpact> findPendingByClearanceForUpdate(@Param("clearanceId") Long clearanceId);

    /**
     * 对某关闭事件全部待处理标记加写锁（关闭范围调整 / 取消时使用）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from ClosureImpact i
            where i.closure.id = :closureId
              and i.status = com.chris64233.cc.airspace.domain.ClosureImpactStatus.PENDING
            order by i.id
            """)
    List<ClosureImpact> findPendingByClosureForUpdate(@Param("closureId") Long closureId);
}
