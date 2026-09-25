package com.chris64233.cc.airspace.repo;

import com.chris64233.cc.airspace.domain.AirSegment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AirSegmentRepository extends JpaRepository<AirSegment, String> {

    Optional<AirSegment> findByCode(String code);

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = AvailableSettings.JAKARTA_LOCK_TIMEOUT, value = "10000"))
    @Query("select s from AirSegment s where s.code in :codes order by s.code asc")
    List<AirSegment> findAllByCodeForUpdate(@Param("codes") Collection<String> codes);
}
