package com.chris64233.cc.airspace.domain;

/**
 * 受关闭影响航段的处理状态。
 */
public enum ClosureImpactStatus {
    /** 尚未处理：航班仍未进入该航段，可通过关闭改道调整。 */
    PENDING,
    /** 已处理：航班已确认替代航线，旧方案不可再改。 */
    HANDLED,
    /** 已失效：关闭取消（限制解除），无需处理。 */
    RELEASED,
    /** 已失效：航班已飞入或飞完该航段，不能反向修改。 */
    EXPIRED
}
