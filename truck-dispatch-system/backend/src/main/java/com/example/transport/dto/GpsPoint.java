package com.example.transport.dto;

/**
 * GPS 定位点视图（gps_data 表 JOIN vehicle 后的结果，供前端轨迹/设备面板展示）
 *
 * @param gpsId      定位数据 ID
 * @param vehicleId   车辆 ID
 * @param plateNumber 车牌号
 * @param longitude   经度
 * @param latitude    纬度
 * @param speed       速度（km/h）
 * @param heading     方向角
 * @param simulated   是否为仿真产生（sim_id 非空=true；真实北斗设备上报=false）
 * @param timestamp   定位时间
 */
public record GpsPoint(Long gpsId, Integer vehicleId, String plateNumber, Double longitude, Double latitude,
                       Double speed, Double heading, Boolean simulated, String timestamp) {
}
