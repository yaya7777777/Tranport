package com.example.transport.dto;

/**
 * 设置车辆状态请求体
 *
 * @param statusCode 目标状态编码（IDLE/LOADING/UNLOADING/TRANSPORT/REFUEL/MAINTAIN）
 */
public record VehicleStatusRequest(String statusCode) {
}
