package com.chris64233.cc.airspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "air_segment",
        uniqueConstraints = @UniqueConstraint(name = "uk_air_segment_code", columnNames = "code"))
public class AirSegment {

    @Id
    @Column(name = "code", length = 64, nullable = false)
    private String code;

    @Column(name = "min_altitude", nullable = false)
    private int minAltitude;

    @Column(name = "max_altitude", nullable = false)
    private int maxAltitude;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    protected AirSegment() {
    }

    public AirSegment(String code, int minAltitude, int maxAltitude, int capacity) {
        this.code = code;
        this.minAltitude = minAltitude;
        this.maxAltitude = maxAltitude;
        this.capacity = capacity;
    }

    public String getCode() {
        return code;
    }

    public int getMinAltitude() {
        return minAltitude;
    }

    public int getMaxAltitude() {
        return maxAltitude;
    }

    public int getCapacity() {
        return capacity;
    }
}
