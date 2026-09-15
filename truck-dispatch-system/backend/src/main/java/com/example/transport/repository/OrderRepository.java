package com.example.transport.repository;
import com.example.transport.dto.OrderSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public class OrderRepository {
 private final JdbcTemplate jdbc;
 public OrderRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public List<OrderSummary> findAll(){
  String sql="SELECT co.order_id,co.order_no,c.cargo_name,cc.category_name cargo_type,co.quantity,"+
   "p1.poi_name origin,p2.poi_name destination,r.distance,r.estimated_time,d.name driver_name,v.plate_number,"+
   "vt.type_name vehicle_type,co.status order_status,dis.status dispatch_status FROM cargo_order co "+
   "JOIN cargo c ON co.cargo_id=c.cargo_id JOIN cargo_category cc ON c.category_id=cc.category_id "+
   "JOIN poi p1 ON co.origin_poi_id=p1.poi_id JOIN poi p2 ON co.destination_poi_id=p2.poi_id "+
   "LEFT JOIN route r ON co.route_id=r.route_id LEFT JOIN dispatch dis ON co.order_id=dis.order_id "+
   "LEFT JOIN driver d ON dis.driver_id=d.driver_id LEFT JOIN vehicle v ON dis.vehicle_id=v.vehicle_id "+
   "LEFT JOIN vehicle_type vt ON v.type_id=vt.type_id ORDER BY co.order_id DESC";
  return jdbc.query(sql,(rs,n)->new OrderSummary(rs.getInt("order_id"),rs.getString("order_no"),
   rs.getString("cargo_name"),rs.getString("cargo_type"),rs.getObject("quantity",Double.class),
   rs.getString("origin"),rs.getString("destination"),rs.getObject("distance",Double.class),
   rs.getObject("estimated_time",Integer.class),rs.getString("driver_name"),rs.getString("plate_number"),
   rs.getString("vehicle_type"),rs.getString("order_status"),rs.getString("dispatch_status")));
 }
}
