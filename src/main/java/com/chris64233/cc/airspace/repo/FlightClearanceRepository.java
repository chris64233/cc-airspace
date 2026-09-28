package com.chris64233.cc.airspace.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.ClearanceStatus;
import com.chris64233.cc.airspace.domain.FlightClearance;

public interface FlightClearanceRepository extends JpaRepository<FlightClearance, Long> {

    /**
     * 一次性抓取许可、逐航段占用与航段本体（结果按航段顺序排序），避免视图组装时的懒加载问题。
     */
    @Query("""
            select distinct c from FlightClearance c
            left join fetch c.reservations r
            left join fetch r.segment
            where c.externalNo = :externalNo
            """)
    Optional<FlightClearance> findDetailByExternalNo(@Param("externalNo") String externalNo);

    Optional<FlightClearance> findByExternalNo(String externalNo);

    /**
     * 对许可行加写锁：改道、飞行开始、位置上报等变更操作先取该锁，
     * 使同一许可上的并发变更串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FlightClearance c where c.externalNo = :externalNo")
    Optional<FlightClearance> findByExternalNoForUpdate(@Param("externalNo") String externalNo);

    /**
     * 按主键升序对一批许可加写锁（关闭识别 / 范围重算时使用），
     * 与单个许可变更事务保持同一加锁顺序，避免死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FlightClearance c where c.id in :ids order by c.id")
    List<FlightClearance> findAllByIdForUpdateOrderById(@Param("ids") List<Long> ids);

    /**
     * 关闭识别候选：在关闭时间窗内仍 ACTIVE、且航线占用了关闭区域任一航段的许可
     *（含占用与航段明细）。可能返回重复行，由调用方按 id 去重并排序后加锁。
     */
    @Query("""
            select distinct c from FlightClearance c
            join fetch c.reservations r
            join fetch r.segment s
            where c.status = :active
              and c.startTime < :endTime
              and c.endTime > :startTime
              and s.id in :segmentIds
            """)
    List<FlightClearance> findActiveCandidatesUsingSegments(@Param("active") ClearanceStatus active,
                                                            @Param("startTime") Instant startTime,
                                                            @Param("endTime") Instant endTime,
                                                            @Param("segmentIds") List<Long> segmentIds);

    /**
     * 原子条件更新：只有当前状态仍为 ACTIVE 时才置为 CANCELLED。
     * 返回 0 表示许可不存在或已取消，调用方据此实现取消幂等与并发安全。
     */
    @Modifying
    @Query("update FlightClearance c set c.status = :cancelled, c.cancelledAt = :cancelledAt "
            + "where c.externalNo = :externalNo and c.status = :active")
    int cancelIfActive(@Param("externalNo") String externalNo,
                       @Param("active") ClearanceStatus active,
                       @Param("cancelled") ClearanceStatus cancelled,
                       @Param("cancelledAt") Instant cancelledAt);
}
