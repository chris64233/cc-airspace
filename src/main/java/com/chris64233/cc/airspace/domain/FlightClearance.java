package com.chris64233.cc.airspace.domain;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "flight_clearance",
        uniqueConstraints = @UniqueConstraint(name = "uk_clearance_external_no", columnNames = "external_no"))
public class FlightClearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "external_no", length = 64, nullable = false)
    private String externalNo;

    @Column(name = "aircraft", length = 64, nullable = false)
    private String aircraft;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ClearanceStatus status;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "clearance", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    private List<ClearanceLeg> legs = new ArrayList<>();

    protected FlightClearance() {
    }

    public FlightClearance(String externalNo, String aircraft, Instant startTime, Instant endTime) {
        this.externalNo = externalNo;
        this.aircraft = aircraft;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = ClearanceStatus.APPROVED;
    }

    public void addLeg(String segmentCode, int seq, int altitude) {
        legs.add(new ClearanceLeg(this, segmentCode, seq, altitude));
    }

    public void markCancelled(Instant cancelledAt) {
        this.status = ClearanceStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
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

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<ClearanceLeg> getLegs() {
        return legs;
    }
}
