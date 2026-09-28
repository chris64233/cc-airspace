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

/**
 * 临时空域关闭事件：指定一组航段构成的区域与 [startTime, endTime) 关闭时段。
 * scopeVersion 为范围版本：创建时为 1，每次修订关闭范围 / 时段 +1，
 * 改道方案引用的范围版本过期即拒绝旧方案。
 */
@Entity
@Table(name = "airspace_closure",
        uniqueConstraints = @UniqueConstraint(name = "uk_closure_no", columnNames = "closure_no"))
public class AirspaceClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "closure_no", nullable = false, length = 64)
    private String closureNo;

    @Column(length = 256)
    private String reason;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClosureStatus status;

    @Column(name = "scope_version", nullable = false)
    private int scopeVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "closure", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id asc")
    private List<AirspaceClosureSegment> segments = new ArrayList<>();

    protected AirspaceClosure() {
    }

    public AirspaceClosure(String closureNo, String reason, Instant startTime, Instant endTime,
                           Instant createdAt) {
        this.closureNo = closureNo;
        this.reason = reason;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = ClosureStatus.ACTIVE;
        this.scopeVersion = 1;
        this.createdAt = createdAt;
    }

    public void addSegment(AirSegment segment) {
        this.segments.add(new AirspaceClosureSegment(this, segment));
    }

    public Long getId() {
        return id;
    }

    public String getClosureNo() {
        return closureNo;
    }

    public String getReason() {
        return reason;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public ClosureStatus getStatus() {
        return status;
    }

    public int getScopeVersion() {
        return scopeVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<AirspaceClosureSegment> getSegments() {
        return segments;
    }

    public void revise(String reason, Instant startTime, Instant endTime) {
        this.reason = reason;
        this.startTime = startTime;
        this.endTime = endTime;
        this.scopeVersion++;
    }

    public void markCancelled(Instant cancelledAt) {
        this.status = ClosureStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }
}
