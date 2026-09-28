package com.chris64233.cc.airspace.domain;

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
 * 关闭事件对单个航班单个航段的影响标记。
 *
 * 仅当航班在关闭时段内使用被关闭航段、且确认关闭时尚未进入该航段
 *（航段序号不小于当时的已飞航段数）时才会产生 PENDING 标记；
 * 已进入或已飞完的航段不标记、不可反向修改。
 *
 * 每个标记冻结创建时的航线版本（clearanceVersion 快照）与已飞航段数，
 * 关闭改道确认时若当前航线版本 / 航班状态 / 关闭范围版本已变化，则拒绝旧方案。
 */
@Entity
@Table(name = "closure_impact",
        uniqueConstraints = @UniqueConstraint(name = "uk_closure_impact",
                columnNames = {"closure_id", "clearance_id", "segment_order"}),
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

    @Column(name = "segment_order", nullable = false)
    private int segmentOrder;

    @Column(name = "segment_id", nullable = false)
    private Long segmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ClosureImpactStatus status;

    /** 标记创建（或范围重算）时的航线版本快照。 */
    @Column(name = "clearance_version", nullable = false)
    private int clearanceVersion;

    /** 标记创建时的已飞航段数快照。 */
    @Column(name = "flown_leg_count", nullable = false)
    private int flownLegCount;

    /** 处理该影响的改道业务号（HANDLED 时非空）。 */
    @Column(name = "resolved_reroute_no", length = 64)
    private String resolvedRerouteNo;

    /** 处理后生效的航线版本（HANDLED 时非空）。 */
    @Column(name = "resolved_route_version")
    private Integer resolvedRouteVersion;

    protected ClosureImpact() {
    }

    public ClosureImpact(AirspaceClosure closure, FlightClearance clearance, int segmentOrder,
                         Long segmentId, int clearanceVersion, int flownLegCount) {
        this.closure = closure;
        this.clearance = clearance;
        this.segmentOrder = segmentOrder;
        this.segmentId = segmentId;
        this.status = ClosureImpactStatus.PENDING;
        this.clearanceVersion = clearanceVersion;
        this.flownLegCount = flownLegCount;
    }

    public void markHandled(String resolvedRerouteNo, int resolvedRouteVersion) {
        this.status = ClosureImpactStatus.HANDLED;
        this.resolvedRerouteNo = resolvedRerouteNo;
        this.resolvedRouteVersion = resolvedRouteVersion;
    }

    public void markReleased() {
        this.status = ClosureImpactStatus.RELEASED;
    }

    public void markExpired() {
        this.status = ClosureImpactStatus.EXPIRED;
    }

    public void retarget(int segmentOrder, Long segmentId, int clearanceVersion, int flownLegCount) {
        this.segmentOrder = segmentOrder;
        this.segmentId = segmentId;
        this.clearanceVersion = clearanceVersion;
        this.flownLegCount = flownLegCount;
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

    public int getSegmentOrder() {
        return segmentOrder;
    }

    public Long getSegmentId() {
        return segmentId;
    }

    public ClosureImpactStatus getStatus() {
        return status;
    }

    public int getClearanceVersion() {
        return clearanceVersion;
    }

    public int getFlownLegCount() {
        return flownLegCount;
    }

    public String getResolvedRerouteNo() {
        return resolvedRerouteNo;
    }

    public Integer getResolvedRouteVersion() {
        return resolvedRouteVersion;
    }
}
