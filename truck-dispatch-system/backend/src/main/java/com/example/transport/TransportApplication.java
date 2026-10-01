package com.example.transport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运输车辆调度优化与仿真系统 —— Spring Boot 启动入口
 * <p>
 * 分层结构：
 * <ul>
 *   <li>controller 控制层：接收前端 / 采集器的 HTTP 请求</li>
 *   <li>service    服务层：匹配算法、GIS 计算、调度、仿真引擎等业务逻辑</li>
 *   <li>repository 持久层：基于 JdbcTemplate 访问 MySQL 的 20 张业务表</li>
 *   <li>dto        数据传输对象：用 record 定义接口出入参</li>
 *   <li>config     配置层：跨域等全局配置</li>
 * </ul>
 * {@link EnableScheduling} 开启定时任务支持，仿真引擎的节拍方法依赖该注解。
 * </p>
 */
@EnableScheduling
@SpringBootApplication
public class TransportApplication {

    /**
     * main 方法：JVM 入口，启动内嵌 Tomcat 并初始化 Spring 容器
     *
     * @param args 命令行参数（一般无需传入）
     */
    public static void main(String[] args) {
        SpringApplication.run(TransportApplication.class, args);
    }
}
