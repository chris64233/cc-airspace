package com.chris64233.cc.airspace.repo;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.AirspaceClosure;

public interface AirspaceClosureRepository extends JpaRepository<AirspaceClosure, Long> {

    /**
     * 抓取关闭事件及其航段成员（含航段本体）。
     */
    @Query("""
            select distinct c from AirspaceClosure c
            left join fetch c.segments cs
            left join fetch cs.segment
            where c.eventNo = :eventNo
            """)
    Optional<AirspaceClosure> findDetailByEventNo(@Param("eventNo") String eventNo);

    Optional<AirspaceClosure> findByEventNo(String eventNo);

    /**
     * 对关闭事件行加写锁：创建标记、范围调整、取消、确认关闭改道均先取该锁串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AirspaceClosure c where c.eventNo = :eventNo")
    Optional<AirspaceClosure> findByEventNoForUpdate(@Param("eventNo") String eventNo);
}
