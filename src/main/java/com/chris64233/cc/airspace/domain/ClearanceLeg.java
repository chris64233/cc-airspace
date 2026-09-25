package com.chris64233.cc.airspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "clearance_leg",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_leg_clearance_seq", columnNames = {"clearance_id", "seq"}),
                @UniqueConstraint(name = "uk_leg_clearance_segment",
                        columnNames = {"clearance_id", "segment_code"})
        })
public class ClearanceLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clearance_id", nullable = false)
    private FlightClearance clearance;

    @Column(name = "segment_code", length = 64, nullable = false)
    private String segmentCode;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "altitude", nullable = false)
    private int altitude;

    protected ClearanceLeg() {
    }

    public ClearanceLeg(FlightClearance clearance, String segmentCode, int seq, int altitude) {
        this.clearance = clearance;
        this.segmentCode = segmentCode;
        this.seq = seq;
        this.altitude = altitude;
    }

    public Long getId() {
        return id;
    }

    public FlightClearance getClearance() {
        return clearance;
    }

    public String getSegmentCode() {
        return segmentCode;
    }

    public int getSeq() {
        return seq;
    }

    public int getAltitude() {
        return altitude;
    }
}
