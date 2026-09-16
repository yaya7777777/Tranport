package com.example.transport.dto;

/**
 * 手动调度请求体：把指定订单派给指定车辆
 *
 * @param orderId   运输订单 ID
 * @param vehicleId 选中的车辆 ID（来自智能匹配结果列表）
 */
public record DispatchRequest(Integer orderId, Integer vehicleId) {
}
