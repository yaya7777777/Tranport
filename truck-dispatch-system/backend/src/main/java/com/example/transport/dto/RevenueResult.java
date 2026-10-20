package com.example.transport.dto;

/**
 * 运输收益计算结果（前期简单规则：收入=货值，成本=距离×综合成本单价）
 *
 * @param orderId 订单 ID
 * @param income  预期收入（元）
 * @param cost    预估运输成本（元）
 * @param profit  预期毛收益（元）
 * @param detail  计算口径说明
 */
public record RevenueResult(int orderId, double income, double cost, double profit, String detail) {
}
