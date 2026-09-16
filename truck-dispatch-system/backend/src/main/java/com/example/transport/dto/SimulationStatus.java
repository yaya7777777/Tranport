package com.example.transport.dto;

import java.util.Map;

/**
 * 仿真运行状态摘要
 *
 * @param running         是否正在运行
 * @param simId           仿真记录 ID（对应 simulation_record 表）
 * @param simName         仿真名称
 * @param tick            已执行的仿真节拍数
 * @param totalVehicles   参与车辆总数（"优"要求 10 辆以上循环运行）
 * @param statusCounts    各状态车辆数量分布（key=状态编码，value=数量，6 种状态）
 * @param processedOrders 本次仿真已完成送达的订单数
 * @param pendingOrders   当前待调度订单数
 * @param activeAnomalies 当前持续中的交通异常数
 * @param startTime       仿真开始时间
 */
public record SimulationStatus(Boolean running, Integer simId, String simName, long tick, int totalVehicles,
                               Map<String, Integer> statusCounts, int processedOrders, int pendingOrders,
                               int activeAnomalies, String startTime) {
}
