package com.chris64233.cc.airspace.domain;

/**
 * 受关闭事件影响的航段处理状态：
 * PENDING —— 航班尚未进入该航段，等待处理（改道或等待关闭取消）；
 * REROUTED —— 已在确认替代航线的事务中改道，关闭取消不会回滚。
 */
public enum ClosureImpactStatus {
    PENDING,
    REROUTED
}
