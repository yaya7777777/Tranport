package com.example.transport.dto;

/**
 * 增加需求（运输订单）请求体：基于工厂-仓库关系生成
 *
 * @param factoryId    发货工厂 ID
 * @param warehouseId  收货仓库 ID（须与工厂存在厂仓关系）
 * @param cargoId      货物 ID
 * @param quantity     数量（件/吨，可空默认 1）
 * @param priority     优先级 1高/2中/3低（可空默认 2）
 * @param remark       备注（可空）
 */
public record OrderCreateRequest(Integer factoryId, Integer warehouseId, Integer cargoId,
                                 Double quantity, Integer priority, String remark) {
}
