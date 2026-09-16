package com.example.transport.dto;

/**
 * 仿真中某辆车的实时位置与状态（仿真沙盘每 1.5 秒拉取一次）
 *
 * @param vehicleId    车辆 ID
 * @param plateNumber  车牌号
 * @param vehicleType  车型名称
 * @param driverName   司机姓名
 * @param status       状态编码（IDLE/LOADING/TRANSPORT/UNLOADING/REFUEL/MAINTAIN 共 6 种）
 * @param longitude    当前经度（行驶中按路线线性插值得到）
 * @param latitude     当前纬度
 * @param speed        当前速度（km/h，受交通异常影响会下降）
 * @param heading      行驶方向角
 * @param remainingFuel 剩余油量（L）
 * @param orderNo      当前执行的订单号（空闲时为空）
 * @param originName   起点名称
 * @param destName     终点名称
 * @param progress     运输进度（0.0-1.0，用于前端画已完成轨迹）
 */
public record VehiclePosition(Integer vehicleId, String plateNumber, String vehicleType, String driverName,
                              String status, Double longitude, Double latitude, Double speed, Double heading,
                              Double remainingFuel, String orderNo, String originName, String destName,
                              Double progress) {
}
