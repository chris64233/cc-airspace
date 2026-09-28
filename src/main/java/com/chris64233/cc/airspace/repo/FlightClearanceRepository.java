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
     * 许可时间窗标量投影（不把实体装入一级缓存），供事务在加锁前探测加锁集合。
     */
    interface ClearanceWindow {
        Long getId();

        Instant getStartTime();

        Instant getEndTime();
    }

    @Query("""
            select c.id as id, c.startTime as startTime, c.endTime as endTime
            from FlightClearance c where c.externalNo = :externalNo
            """)
    Optional<ClearanceWindow> findWindowByExternalNo(@Param("externalNo") String externalNo);

    @Query("select c.id from FlightClearance c where c.externalNo = :externalNo")
    Optional<Long> findIdByExternalNo(@Param("externalNo") String externalNo);

    /**
     * 按主键升序批量加写锁，与航段锁、关闭事件锁遵循同一全局顺序，避免死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FlightClearance c where c.id in :ids order by c.id")
    List<FlightClearance> findAllByIdForUpdateOrderById(@Param("ids") List<Long> ids);

    /**
     * 扫描时间窗与 [startTime, endTime) 重叠、航线经过给定航段集合的有效许可，仅返回主键。
     * 标量投影不污染一级缓存，创建 / 修订关闭事件时先取主键再加锁读取。
     */
    @Query("""
            select distinct c.id from FlightClearance c
            join c.reservations r
            where c.status = com.chris64233.cc.airspace.domain.ClearanceStatus.ACTIVE
              and r.segment.id in :segmentIds
              and c.startTime < :endTime
              and c.endTime > :startTime
            """)
    List<Long> findActiveIdsOnSegments(@Param("segmentIds") List<Long> segmentIds,
                                       @Param("startTime") Instant startTime,
                                       @Param("endTime") Instant endTime);

    /**
     * 对许可行加写锁：改道、飞行开始、位置上报等变更操作先取该锁，
     * 使同一许可上的并发变更串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FlightClearance c where c.externalNo = :externalNo")
    Optional<FlightClearance> findByExternalNoForUpdate(@Param("externalNo") String externalNo);

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
