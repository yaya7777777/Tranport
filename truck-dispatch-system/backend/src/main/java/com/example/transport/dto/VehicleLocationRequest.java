package com.example.transport.dto;

/**
 * 设置车辆位置请求体
 *
 * @param poiId 目标 POI 站点 ID（车辆位置以站点锚定）
 */
public record VehicleLocationRequest(Integer poiId) {
}
