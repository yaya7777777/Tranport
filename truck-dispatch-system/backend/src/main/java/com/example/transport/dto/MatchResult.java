package com.example.transport.dto;

import java.util.List;

/**
 * 针对某一运输订单的"可派车辆"匹配结果项（按综合评分从高到低排序）
 * <p>评分由四部分组成：车型是否优选、载重容积贴合度、车辆到发货地的距离、司机常跑路线偏好。</p>
 *
 * @param vehicleId          车辆 ID
 * @param plateNumber        车牌号
 * @param typeName           车型名称
 * @param driverName         当前绑定司机
 * @param currentPoiName     车辆当前所在站点
 * @param distanceToOriginKm 车辆当前位置到订单发货地的直线距离（km）
 * @param remainingFuel      剩余油量（L）
 * @param score              综合评分（0-100，越高越优）
 * @param reasons            评分明细（人类可读的加分/限制原因列表）
 */
public record MatchResult(Integer vehicleId, String plateNumber, String typeName, String driverName,
                          String currentPoiName, Double distanceToOriginKm, Double remainingFuel,
                          Double score, List<String> reasons) {
}
