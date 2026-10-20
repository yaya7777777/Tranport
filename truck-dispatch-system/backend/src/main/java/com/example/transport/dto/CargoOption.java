package com.example.transport.dto;

/**
 * 货物选项（内部服务使用）：订单匹配与自动生成货源时需要的货物关键字段
 *
 * @param cargoId    货物 ID
 * @param cargoName  货物名称
 * @param categoryId 货物分类 ID（决定可装车型）
 * @param weight     单批重量（kg）
 * @param volume     单批体积（m³）
 */
public record CargoOption(Integer cargoId, String cargoName, Integer categoryId,
                          Double weight, Double volume) {
}
