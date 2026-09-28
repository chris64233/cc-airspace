package com.chris64233.cc.airspace.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.ClosureImpact;
import com.chris64233.cc.airspace.domain.ClosureImpactStatus;

public interface ClosureImpactRepository extends JpaRepository<ClosureImpact, Long> {

    /**
     * 某关闭事件的全部受影响航段标记（含许可、航段、关闭事件），按主键升序。
     */
    @Query("""
            select distinct i from ClosureImpact i
            join fetch i.clearance c
            join fetch i.segment
            join fetch i.closure
            where i.closure.closureNo = :closureNo
            order by i.id
            """)
    List<ClosureImpact> findDetailByClosureNo(@Param("closureNo") String closureNo);

    /**
     * 某许可的全部受影响标记（含关闭事件、航段、许可），按主键升序。
     */
    @Query("""
            select distinct i from ClosureImpact i
            join fetch i.clearance c
            join fetch i.closure cl
            join fetch i.segment
            where i.clearance.externalNo = :externalNo
            order by i.id
            """)
    List<ClosureImpact> findDetailByClearanceExternalNo(@Param("externalNo") String externalNo);

    /**
     * 某许可挂在哪些关闭事件下（仅关闭事件主键，标量投影不污染一级缓存），
     * 位置上报事务据此先取 level0 关闭事件锁。
     */
    @Query("""
            select distinct i.closure.id from ClosureImpact i
            where i.clearance.externalNo = :externalNo
            order by i.closure.id
            """)
    List<Long> findClosureIdsByClearanceExternalNo(@Param("externalNo") String externalNo);

    /**
     * 某关闭事件对某许可的全部标记（加锁读取，行随关闭事件 / 许可事务串行化）。
     */
    @Query("""
            select i from ClosureImpact i
            where i.closure.id = :closureId and i.clearance.id = :clearanceId
            order by i.id
            """)
    List<ClosureImpact> findByClosureAndClearance(@Param("closureId") Long closureId,
                                                  @Param("clearanceId") Long clearanceId);

    /**
     * 删除关闭事件下所有尚未处理（PENDING）的标记——关闭取消或修订范围时使用，
     * 已 REROUTED 的标记保留（不自动撤销已完成改道）。
     */
    @Modifying
    @Query("delete from ClosureImpact i "
            + "where i.closure.id = :closureId and i.status = :pending")
    int deletePendingByClosure(@Param("closureId") Long closureId,
                               @Param("pending") ClosureImpactStatus pending);

    /**
     * 许可取消 / 完成时丢弃其尚未处理的标记（已执行的改道记录保留）。
     */
    @Modifying
    @Query("delete from ClosureImpact i "
            + "where i.clearance.id = :clearanceId and i.status = :pending")
    int deletePendingByClearance(@Param("clearanceId") Long clearanceId,
                                 @Param("pending") ClosureImpactStatus pending);

    /**
     * 航班已飞入某航段后，丢弃该航段及其之前所有尚未处理的标记
     * （已进入关闭区域属既成运行事实，不能反向修改）。
     */
    @Modifying
    @Query("delete from ClosureImpact i "
            + "where i.clearance.id = :clearanceId and i.status = :pending "
            + "and i.legOrder < :flownLegCount")
    int deletePendingFlownByClearance(@Param("clearanceId") Long clearanceId,
                                      @Param("pending") ClosureImpactStatus pending,
                                      @Param("flownLegCount") int flownLegCount);
}
