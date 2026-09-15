package com.example.transport.repository;
import com.example.transport.dto.VehicleSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public class VehicleRepository {
 private final JdbcTemplate jdbc;
 public VehicleRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public List<VehicleSummary> findAll(){
  String sql = "SELECT v.vehicle_id,v.plate_number,vt.type_name vehicle_type,d.name driver_name,p.poi_name current_location,"+
   "v.current_status,v.remaining_fuel,v.estimated_range,v.total_mileage FROM vehicle v "+
   "LEFT JOIN vehicle_type vt ON v.type_id=vt.type_id LEFT JOIN driver d ON v.driver_id=d.driver_id "+
   "LEFT JOIN poi p ON v.current_poi_id=p.poi_id ORDER BY v.vehicle_id";
  return jdbc.query(sql,(rs,n)->new VehicleSummary(rs.getInt("vehicle_id"),rs.getString("plate_number"),
   rs.getString("vehicle_type"),rs.getString("driver_name"),rs.getString("current_location"),
   rs.getString("current_status"),rs.getObject("remaining_fuel",Double.class),
   rs.getObject("estimated_range",Double.class),rs.getObject("total_mileage",Double.class)));
 }
}
