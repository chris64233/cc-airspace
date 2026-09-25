package com.chris64233.cc.airspace.repo;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
