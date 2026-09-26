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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * 航线业务版本：初始为 1，每次改道 +1。改道申请必须引用该版本，
     * 版本不一致说明许可已被并发修改，拒绝改道且不影响原占用。
     */
    @Column(name = "version", nullable = false)
    private int version;

    /**
     * 已飞过的航段数量（从 0 号航段起）。改道起点不得小于该值，
     * 已飞过的航段不可改写。
     */
    @Column(name = "flown_leg_count", nullable = false)
    private int flownLegCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @OneToMany(mappedBy = "clearance", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("segmentOrder asc")
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
        this.createdAt = createdAt;
        this.version = 1;
        this.flownLegCount = 0;
    }

    public void addReservation(int segmentOrder, AirSegment segment, int altitude) {
        SegmentReservation reservation = new SegmentReservation(this, segmentOrder, segment, altitude);
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public int getVersion() {
        return version;
    }

    public int getFlownLegCount() {
        return flownLegCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void bumpVersion() {
        this.version++;
    }

    public void markStarted(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public void advanceFlownLegCount(int flownLegCount) {
        this.flownLegCount = flownLegCount;
    }

    public void markCompleted() {
        this.status = ClearanceStatus.COMPLETED;
    }

    public void markCancelled(Instant cancelledAt) {
        this.status = ClearanceStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    public List<SegmentReservation> getReservations() {
        return reservations;
    }
}
