package com.chris64233.cc.airspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 航线版本中的单个航段快照（有序）。
 */
@Entity
@Table(name = "route_version_leg",
        uniqueConstraints = @UniqueConstraint(name = "uk_rv_leg_version_order",
                columnNames = {"route_version_id", "segment_order"}),
        indexes = @Index(name = "ix_rv_leg_version", columnList = "route_version_id"))
public class RouteVersionLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_version_id", nullable = false)
    private RouteVersion routeVersion;

    @Column(name = "segment_order", nullable = false)
    private int segmentOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "segment_id", nullable = false)
    private AirSegment segment;

    @Column(nullable = false)
    private int altitude;

    protected RouteVersionLeg() {
    }

    public RouteVersionLeg(RouteVersion routeVersion, int segmentOrder, AirSegment segment, int altitude) {
        this.routeVersion = routeVersion;
        this.segmentOrder = segmentOrder;
        this.segment = segment;
        this.altitude = altitude;
    }

    public Long getId() {
        return id;
    }

    public RouteVersion getRouteVersion() {
        return routeVersion;
    }

    public int getSegmentOrder() {
        return segmentOrder;
    }

    public AirSegment getSegment() {
        return segment;
    }

    public int getAltitude() {
        return altitude;
    }
}
