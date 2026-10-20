package com.example.transport.dto;

/**
 * 启动仿真请求体（字段均可空，后端使用默认值：全部启用车辆 + 自动生成货源订单）
 *
 * @param vehicleCount 期望参与的车辆数（超过在库车辆时按实际数量运行）
 * @param autoOrders   是否根据厂仓关系自动生成货源订单（默认 true，保证车辆可无限循环）
 */
public record SimStartRequest(Integer vehicleCount, Boolean autoOrders) {
}
