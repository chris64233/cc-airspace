package com.chris64233.cc.airspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "air_segment",
        uniqueConstraints = @UniqueConstraint(name = "uk_air_segment_code", columnNames = "code"))
public class AirSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(name = "min_altitude", nullable = false)
    private int minAltitude;

    @Column(name = "max_altitude", nullable = false)
    private int maxAltitude;

    @Column(nullable = false)
    private int capacity;

    protected AirSegment() {
    }

    public AirSegment(String code, int minAltitude, int maxAltitude, int capacity) {
        this.code = code;
        this.minAltitude = minAltitude;
        this.maxAltitude = maxAltitude;
        this.capacity = capacity;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getMinAltitude() {
        return minAltitude;
    }

    public void setMinAltitude(int minAltitude) {
        this.minAltitude = minAltitude;
    }

    public int getMaxAltitude() {
        return maxAltitude;
    }

    public void setMaxAltitude(int maxAltitude) {
        this.maxAltitude = maxAltitude;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }
}
