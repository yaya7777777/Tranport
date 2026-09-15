package com.example.transport.controller;
import com.example.transport.dto.DashboardStats;
import com.example.transport.repository.DashboardRepository;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins="http://localhost:5173")
public class DashboardController {
 private final DashboardRepository repository;
 public DashboardController(DashboardRepository repository){this.repository=repository;}
 @GetMapping("/stats") public DashboardStats stats(){return repository.stats();}
 @GetMapping("/health") public String health(){return "transport backend is running";}
}
