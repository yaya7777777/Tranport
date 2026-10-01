package com.example.transport.dto;

/**
 * 运行总览页的统计数据（车辆 6 状态分布 + 订单/调度 + 基础数据规模）
 *
 * @param vehicleCount         启用车辆总数
 * @param idleCount            空闲车辆数
 * @param loadingCount         装载中车辆数
 * @param transportingCount    运输中车辆数
 * @param unloadingCount       卸货中车辆数
 * @param refuelCount          加油中车辆数
 * @param maintenanceCount     保养中车辆数
 * @param pendingOrderCount    待处理订单数
 * @param activeDispatchCount  执行中的调度数
 * @param poiCount             POI 站点总数（任务书按数量评定等级：500 中 / 1000 良）
 * @param activeAnomalyCount   持续中的交通异常数
 */
public record DashboardStats(long vehicleCount, long idleCount, long loadingCount, long transportingCount,
                             long unloadingCount, long refuelCount, long maintenanceCount,
                             long pendingOrderCount, long activeDispatchCount,
                             long poiCount, long activeAnomalyCount) {
}
