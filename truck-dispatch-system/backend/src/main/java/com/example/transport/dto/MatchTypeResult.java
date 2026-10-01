package com.example.transport.dto;

/**
 * "货物 -> 车型"的匹配结果项（任务书要求的"匹配或筛选调用函数"输出之一）
 *
 * @param typeId    车型 ID
 * @param typeName  车型名称
 * @param preferred 是否优选车型（来自 cargo_vehicle_type_match.is_preferred）
 * @param reason    命中/筛选的文字说明（便于前端展示与答辩讲解）
 */
public record MatchTypeResult(Integer typeId, String typeName, Boolean preferred, String reason) {
}
