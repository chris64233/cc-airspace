package com.chris64233.cc.airspace.domain.FlightClearance.java
package com.chris64233.cc.airspace.repo;

import com.chris64233.cc.airspace.domain.FlightClearance;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface FlightClearanceRepository extends JpaRepository<FlightClearance, Long> {

    Optional<FlightClearance> findByExternalNo(String externalNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = AvailableSettings.JAKARTA_LOCK_TIMEOUT, value = "10000"))
    @Query("select c from FlightClearance c where c.externalNo = :externalNo")
    Optional<FlightClearance> findByExternalNoForUpdate(@Param("externalNo") String externalNo);
}
