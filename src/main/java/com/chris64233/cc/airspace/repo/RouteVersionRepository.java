package com.chris64233.cc.airspace.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.airspace.domain.RouteVersion;

public interface RouteVersionRepository extends JpaRepository<RouteVersion, Long> {

    /**
     * 按改道业务号抓取版本（含航段与航段本体、所属许可），用于幂等回放时比对内容。
     */
    @Query("""
            select distinct rv from RouteVersion rv
            left join fetch rv.legs l
            left join fetch l.segment
            left join fetch rv.clearance
            where rv.rerouteNo = :rerouteNo
            """)
    Optional<RouteVersion> findDetailByRerouteNo(@Param("rerouteNo") String rerouteNo);

    /**
     * 某许可的全部航线版本（含航段），按版本号升序。
     */
    @Query("""
            select distinct rv from RouteVersion rv
            left join fetch rv.legs l
            left join fetch l.segment
            where rv.clearance.externalNo = :externalNo
            order by rv.version
            """)
    List<RouteVersion> findHistoryByExternalNo(@Param("externalNo") String externalNo);
}
