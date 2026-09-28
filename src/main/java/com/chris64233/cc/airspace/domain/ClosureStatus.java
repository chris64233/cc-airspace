package com.chris64233.cc.airspace.domain;

/**
 * 临时空域关闭事件状态。
 */
public enum ClosureStatus {
    /** 生效中：范围内航段在关闭时段不可再被新航线使用。 */
    ACTIVE,
    /** 已取消：只解除尚未处理航班的限制，已完成的改道不自动撤销。 */
    CANCELLED
}
