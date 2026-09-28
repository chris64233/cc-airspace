package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;

/**
 * 关闭事件对单个航班单个航段的影响标记视图。
 * status：PENDING（尚未处理，限制仍生效）/ REROUTED（已确认替代航线）。
 * resolvedRerouteNo / resolvedAt 给出最终处理结果；scopeVersionAtMark 为标记时的关闭范围版本。
 */
public record ClosureImpactView(
        String closureNo,
        String externalNo,
        int legOrder,
        String segmentCode,
        String status,
        int scopeVersionAtMark,
        String resolvedRerouteNo,
        Instant createdAt,
        Instant resolvedAt) {
}
