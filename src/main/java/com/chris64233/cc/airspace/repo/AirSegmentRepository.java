package com.chris64233.cc.airspace.repo;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.AirSegment;

public interface AirSegmentRepository extends JpaRepository<AirSegment, Long> {

    Optional<AirSegment> findByCode(String code);

    /**
     * 按航段代码取主键（标量，不装入一级缓存），供事务在加锁前探测加锁集合。
     */
    @Query("select s.id from AirSegment s where s.code = :code")
    Optional<Long> findIdByCode(@Param("code") String code);

    /**
     * 按主键升序对航段加写锁并加锁读取，所有事务均以同一顺序加锁，避免死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AirSegment s where s.id in :ids order by s.id")
    List<AirSegment> findAllByIdForUpdateOrderById(@Param("ids") List<Long> ids);
}
