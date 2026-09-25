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

@Entity
@Table(name = "segment_reservation",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reservation_clearance_order",
                        columnNames = {"clearance_id", "segment_order"}),
                @UniqueConstraint(name = "uk_reservation_clearance_segment",
                        columnNames = {"clearance_id", "segment_id"})
        },
        indexes = @Index(name = "ix_reservation_segment", columnList = "segment_id"))
public class SegmentReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clearance_id", nullable = false)
    private FlightClearance clearance;

    @Column(name = "segment_order", nullable = false)
    private int segmentOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "segment_id", nullable = false)
    private AirSegment segment;

    @Column(nullable = false)
    private int altitude;

    protected SegmentReservation() {
    }

    public SegmentReservation(FlightClearance clearance, int segmentOrder, AirSegment segment, int altitude) {
        this.clearance = clearance;
        this.segmentOrder = segmentOrder;
        this.segment = segment;
        this.altitude = altitude;
    }

    public Long getId() {
        return id;
    }

    public FlightClearance getClearance() {
        return clearance;
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
