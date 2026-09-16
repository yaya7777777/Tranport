package com.example.transport.dto;

/**
 * 北斗定位模块上报的 JSON 报文（对应任务书："经纬度信息经 ARM 上位机以 HTTP 协议 JSON 发送到云端"）。
 * <p>由 tools/modbus_collector.py 采集并 POST 到 /api/gps/report。</p>
 *
 * @param vehicleCode 车牌号（与 vehicle.plate_number 对应；与 vehicleId 二选一即可）
 * @param vehicleId   车辆 ID（可空，为空时按车牌号查找）
 * @param longitude   经度
 * @param latitude    纬度
 * @param speed       速度（km/h）
 * @param heading     方向角（0-360 度）
 * @param timestamp   定位时间戳（可空，为空时由服务器取当前时间）
 */
public record GpsReport(String vehicleCode, Integer vehicleId, Double longitude, Double latitude,
                        Double speed, Double heading, String timestamp) {
}
