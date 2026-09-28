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

/**
 * 关闭事件覆盖的航段（区域成员）。
 */
@Entity
@Table(name = "airspace_closure_segment",
        uniqueConstraints = @UniqueConstraint(name = "uk_closure_segment",
                columnNames = {"closure_id", "segment_id"}),
        indexes = @Index(name = "ix_closure_segment_segment", columnList = "segment_id"))
public class AirspaceClosureSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closure_id", nullable = false)
    private AirspaceClosure closure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "segment_id", nullable = false)
    private AirSegment segment;

    protected AirspaceClosureSegment() {
    }

    public AirspaceClosureSegment(AirspaceClosure closure, AirSegment segment) {
        this.closure = closure;
        this.segment = segment;
    }

    public Long getId() {
        return id;
    }

    public AirspaceClosure getClosure() {
        return closure;
    }

    public AirSegment getSegment() {
        return segment;
    }
}
