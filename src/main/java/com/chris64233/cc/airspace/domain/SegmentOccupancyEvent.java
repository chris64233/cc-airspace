package com.chris64233.cc.airspace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 逐航段占用事件：提交、改道、取消时按航段记录 ACQUIRE / RELEASE，
 * 形成完整的容量占用审计轨迹。
 */
@Entity
@Table(name = "segment_occupancy_event",
        indexes = @Index(name = "ix_occupancy_event_clearance", columnList = "clearance_id"))
public class SegmentOccupancyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clearance_id", nullable = false)
    private FlightClearance clearance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "segment_id", nullable = false)
    private AirSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private OccupancyEventType eventType;

    @Column(nullable = false)
    private int altitude;

    /** 事件生效后的航线版本号。 */
    @Column(name = "route_version", nullable = false)
    private int routeVersion;

    @Column(name = "reroute_no", length = 64)
    private String rerouteNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SegmentOccupancyEvent() {
    }

    public SegmentOccupancyEvent(FlightClearance clearance, AirSegment segment, OccupancyEventType eventType,
                                 int altitude, int routeVersion, String rerouteNo, Instant createdAt) {
        this.clearance = clearance;
        this.segment = segment;
        this.eventType = eventType;
        this.altitude = altitude;
        this.routeVersion = routeVersion;
        this.rerouteNo = rerouteNo;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public FlightClearance getClearance() {
        return clearance;
    }

    public AirSegment getSegment() {
        return segment;
    }

    public OccupancyEventType getEventType() {
        return eventType;
    }

    public int getAltitude() {
        return altitude;
    }

    public int getRouteVersion() {
        return routeVersion;
    }

    public String getRerouteNo() {
        return rerouteNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
