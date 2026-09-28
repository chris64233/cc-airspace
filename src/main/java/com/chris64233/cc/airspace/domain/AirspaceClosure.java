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
 * 临时空域关闭事件：指定一组航段（区域）与关闭时段 [startTime, endTime)。
 * 关闭业务号唯一，作为创建幂等键。
 *
 * scopeVersion 在关闭范围（航段集合 / 时段）每次变更时 +1，
 * 关闭改道必须引用该版本，版本不一致说明范围已变化，旧方案拒绝执行。
 */
@Entity
@Table(name = "airspace_closure",
        uniqueConstraints = @UniqueConstraint(name = "uk_closure_event_no", columnNames = "event_no"))
public class AirspaceClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_no", nullable = false, length = 64)
    private String eventNo;

    @Column(length = 256)
    private String reason;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClosureStatus status;

    /**
     * 关闭范围版本：创建为 1，每次范围调整 +1。改道确认时校验，过期方案拒绝。
     */
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

    public AirspaceClosure(String eventNo, String reason, Instant startTime, Instant endTime,
                           Instant createdAt) {
        this.eventNo = eventNo;
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

    public void clearSegments() {
        this.segments.clear();
    }

    public void bumpScopeVersion() {
        this.scopeVersion++;
    }

    public void reschedule(Instant startTime, Instant endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void markCancelled(Instant cancelledAt) {
        this.status = ClosureStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventNo() {
        return eventNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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
}
