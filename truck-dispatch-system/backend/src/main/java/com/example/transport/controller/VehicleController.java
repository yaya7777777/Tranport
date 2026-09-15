package com.example.transport.controller;
import com.example.transport.dto.VehicleSummary;
import com.example.transport.repository.VehicleRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/vehicles")
@CrossOrigin(origins="http://localhost:5173")
public class VehicleController {
 private final VehicleRepository repository;
 public VehicleController(VehicleRepository repository){this.repository=repository;}
 @GetMapping public List<VehicleSummary> list(){return repository.findAll();}
}
