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
import jakarta.persistence.UniqueConstraint;

/**
 * 关闭事件对单个受影响航段的标记记录。只标记标记时刻航班尚未进入
 * （航段序号 &gt;= flownLegCount）且时间窗与关闭时段重叠的航段；
 * 已进入或已飞完的航段不产生记录，之后也不可反向修改。
 */
@Entity
@Table(name = "closure_impact",
        uniqueConstraints = @UniqueConstraint(name = "uk_closure_impact_leg_scope",
                columnNames = {"closure_id", "clearance_id", "leg_order", "scope_version_at_mark"}),
        indexes = {
                @Index(name = "ix_closure_impact_closure", columnList = "closure_id"),
                @Index(name = "ix_closure_impact_clearance", columnList = "clearance_id")
        })
public class ClosureImpact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closure_id", nullable = false)
    private AirspaceClosure closure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clearance_id", nullable = false)
    private FlightClearance clearance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "segment_id", nullable = false)
    private AirSegment segment;

    /** 标记时刻该航段在许可航线中的序号。 */
    @Column(name = "leg_order", nullable = false)
    private int legOrder;

    /** 标记时刻的关闭范围版本，改道方案据此识别旧方案。 */
    @Column(name = "scope_version_at_mark", nullable = false)
    private int scopeVersionAtMark;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClosureImpactStatus status;

    @Column(name = "resolved_reroute_no", length = 64)
    private String resolvedRerouteNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ClosureImpact() {
    }

    public ClosureImpact(AirspaceClosure closure, FlightClearance clearance, AirSegment segment,
                         int legOrder, int scopeVersionAtMark, Instant createdAt) {
        this.closure = closure;
        this.clearance = clearance;
        this.segment = segment;
        this.legOrder = legOrder;
        this.scopeVersionAtMark = scopeVersionAtMark;
        this.status = ClosureImpactStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markRerouted(String rerouteNo, Instant resolvedAt) {
        this.status = ClosureImpactStatus.REROUTED;
        this.resolvedRerouteNo = rerouteNo;
        this.resolvedAt = resolvedAt;
    }

    public Long getId() {
        return id;
    }

    public AirspaceClosure getClosure() {
        return closure;
    }

    public FlightClearance getClearance() {
        return clearance;
    }

    public AirSegment getSegment() {
        return segment;
    }

    public int getLegOrder() {
        return legOrder;
    }

    public int getScopeVersionAtMark() {
        return scopeVersionAtMark;
    }

    public ClosureImpactStatus getStatus() {
        return status;
    }

    public String getResolvedRerouteNo() {
        return resolvedRerouteNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
