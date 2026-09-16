package com.example.transport.dto;

/**
 * 车辆状态变更日志视图（对应 vehicle_status_log 表，记录车辆按"状态转换规则表"发生的每次跳转）
 *
 * @param logId       日志 ID
 * @param vehicleId   车辆 ID
 * @param plateNumber 车牌号
 * @param fromStatus  原状态编码（初始状态时为空）
 * @param toStatus    新状态编码
 * @param poiName     变更发生所在站点
 * @param changeTime  变更时间
 * @param remark      备注（如所执行的订单号、异常说明）
 */
public record StatusLogView(Long logId, Integer vehicleId, String plateNumber, String fromStatus,
                            String toStatus, String poiName, String changeTime, String remark) {
}
