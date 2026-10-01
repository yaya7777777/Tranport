package com.example.transport.dto;
public record VehicleSummary(Integer vehicleId,String plateNumber,String vehicleType,String driverName,
 String currentLocation,String currentStatus,Double remainingFuel,Double estimatedRange,Double totalMileage) {}
