package com.chris64233.cc.airspace.web.dto;

/**
 * 关闭事件对单个航班单个航段的影响标记视图。
 */
public record ClosureImpactView(
        String closureEventNo,
        int closureScopeVersion,
        String externalNo,
        int segmentOrder,
        String segmentCode,
        String status,
        int clearanceVersion,
        int flownLegCount,
        String resolvedRerouteNo,
        Integer resolvedRouteVersion) {
}
