package com.example.transport.controller;
import com.example.transport.dto.OrderSummary;
import com.example.transport.repository.OrderRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins="http://localhost:5173")
public class OrderController {
 private final OrderRepository repository;
 public OrderController(OrderRepository repository){this.repository=repository;}
 @GetMapping public List<OrderSummary> list(){return repository.findAll();}
}
