package com.example.transport.dto;
public record OrderSummary(Integer orderId,String orderNo,String cargoName,String cargoType,Double quantity,
 String origin,String destination,Double distance,Integer estimatedTime,String driverName,String plateNumber,
 String vehicleType,String orderStatus,String dispatchStatus) {}
