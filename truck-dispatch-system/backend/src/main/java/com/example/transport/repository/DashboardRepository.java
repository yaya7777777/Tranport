package com.example.transport.repository;
import com.example.transport.dto.DashboardStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class DashboardRepository {
 private final JdbcTemplate jdbc;
 public DashboardRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public DashboardStats stats(){
  long v=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE status=1",Long.class);
  long i=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE status=1 AND current_status='IDLE'",Long.class);
  long t=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE status=1 AND current_status='TRANSPORT'",Long.class);
  long m=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE status=1 AND current_status='MAINTAIN'",Long.class);
  long p=jdbc.queryForObject("SELECT COUNT(*) FROM cargo_order WHERE status='PENDING'",Long.class);
  long a=jdbc.queryForObject("SELECT COUNT(*) FROM dispatch WHERE status IN ('DISPATCHED','IN_TRANSIT')",Long.class);
  return new DashboardStats(v,i,t,m,p,a);
 }
}
