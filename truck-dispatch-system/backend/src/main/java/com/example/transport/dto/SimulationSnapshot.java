package com.example.transport.dto;

import java.util.List;

/**
 * 仿真沙盘的一次性完整快照（状态 + 所有车辆位置 + 全部交通异常）。
 * POI 站点相对静态，由前端单独请求 /api/pois 加载，避免快照过大。
 *
 * @param status    仿真状态摘要
 * @param vehicles  全部参与车辆的实时位置列表
 * @param anomalies 当前交通异常列表
 */
public record SimulationSnapshot(SimulationStatus status, List<VehiclePosition> vehicles,
                                 List<AnomalyView> anomalies) {
}
