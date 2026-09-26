package com.chris64233.cc.airspace.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "flight_clearance",
        uniqueConstraints = @UniqueConstraint(name = "uk_clearance_external_no", columnNames = "external_no"))
public class FlightClearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_no", nullable = false, length = 64)
    private String externalNo;

    @Column(nullable = false, length = 64)
    private String aircraft;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClearanceStatus status;

    /**
     * 航线版本号：初始为 1，每次改道成功 +1。改道请求必须引用该版本，
     * 版本不一致说明许可已被并发修改，请求必须放弃。
     */
    @Column(nullable = false)
    private int version;

    /**
     * 已飞过的航段数量（航线前 flownLegs 段不可改写）。
     * 改道只能从该位置之后开始衔接。
     */
    @Column(name = "flown_legs", nullable = false)
    private int flownLegs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "clearance", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("routeVersion asc, segmentOrder asc")
    private List<SegmentReservation> reservations = new ArrayList<>();

    protected FlightClearance() {
    }

    public FlightClearance(String externalNo, String aircraft, Instant startTime, Instant endTime,
                           ClearanceStatus status, Instant createdAt) {
        this.externalNo = externalNo;
        this.aircraft = aircraft;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.version = 1;
        this.flownLegs = 0;
        this.createdAt = createdAt;
    }

    public void addReservation(int segmentOrder, AirSegment segment, int altitude, int routeVersion,
                               Instant effectiveFrom) {
        SegmentReservation reservation = new SegmentReservation(this, segmentOrder, segment, altitude,
                routeVersion, effectiveFrom);
        this.reservations.add(reservation);
    }

    public Long getId() {
        return id;
    }

    public String getExternalNo() {
        return externalNo;
    }

    public String getAircraft() {
        return aircraft;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public ClearanceStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public int getFlownLegs() {
        return flownLegs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void markCancelled(Instant cancelledAt) {
        this.status = ClearanceStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    public List<SegmentReservation> getReservations() {
        return reservations;
    }
}
