package com.example.transport.dto;

/**
 * 交通异常视图（对应 traffic_anomaly 表，仿真沙盘上以警告图标展示）
 *
 * @param anomalyId   异常 ID
 * @param anomalyType 异常类型（CONGESTION 拥堵 / ACCIDENT 事故 / CONSTRUCTION 施工 / WEATHER 天气）
 * @param severity    严重程度（1 轻微 / 2 中等 / 3 严重）
 * @param longitude   异常发生点经度
 * @param latitude    异常发生点纬度
 * @param description 异常描述
 * @param startTime   开始时间
 * @param active      是否仍在持续（end_time 为空）
 */
public record AnomalyView(Integer anomalyId, String anomalyType, Integer severity,
                          Double longitude, Double latitude, String description,
                          String startTime, Boolean active) {
}
