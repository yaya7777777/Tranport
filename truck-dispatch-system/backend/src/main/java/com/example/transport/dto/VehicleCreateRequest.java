package com.example.transport.dto;

/**
 * 增加车辆请求体
 *
 * @param plateNumber  车牌号（唯一）
 * @param typeId       车型 ID
 * @param driverId     绑定司机 ID（可空 = 暂不分配）
 * @param poiId        初始所在 POI 站点 ID（可空 = 未定位）
 * @param purchaseDate 购置日期 yyyy-MM-dd（可空默认当天）
 */
public record VehicleCreateRequest(String plateNumber, Integer typeId, Integer driverId,
                                   Integer poiId, String purchaseDate) {
}
