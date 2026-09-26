package com.chris64233.cc.airspace.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 航线历史版本：初始提交记为版本 1，每次改道追加一个完整航线快照。
 * 改道业务号（rerouteNo）带唯一约束，是改道幂等的兜底。
 */
@Entity
@Table(name = "route_version",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_route_version_clearance_version",
                        columnNames = {"clearance_id", "version"}),
                @UniqueConstraint(name = "uk_route_version_reroute_no", columnNames = "reroute_no")
        },
        indexes = @Index(name = "ix_route_version_clearance", columnList = "clearance_id"))
public class RouteVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clearance_id", nullable = false)
    private FlightClearance clearance;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "reroute_no", length = 64)
    private String rerouteNo;

    @Column(name = "from_leg_index", nullable = false)
    private int fromLegIndex;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "routeVersion", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("segmentOrder asc")
    private List<RouteVersionLeg> legs = new ArrayList<>();

    protected RouteVersion() {
    }

    public RouteVersion(FlightClearance clearance, int version, String rerouteNo, int fromLegIndex,
                        Instant effectiveAt, Instant createdAt) {
        this.clearance = clearance;
        this.version = version;
        this.rerouteNo = rerouteNo;
        this.fromLegIndex = fromLegIndex;
        this.effectiveAt = effectiveAt;
        this.createdAt = createdAt;
    }

    public void addLeg(int segmentOrder, AirSegment segment, int altitude) {
        this.legs.add(new RouteVersionLeg(this, segmentOrder, segment, altitude));
    }

    public Long getId() {
        return id;
    }

    public FlightClearance getClearance() {
        return clearance;
    }

    public int getVersion() {
        return version;
    }

    public String getRerouteNo() {
        return rerouteNo;
    }

    public int getFromLegIndex() {
        return fromLegIndex;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<RouteVersionLeg> getLegs() {
        return legs;
    }
}
