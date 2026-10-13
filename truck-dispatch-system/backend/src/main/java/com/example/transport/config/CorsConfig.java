package com.example.transport.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置（CORS）
 * <p>
 * 前端 Vite 开发服务器运行在 http://localhost:5173，
 * 后端 Spring Boot 运行在 http://localhost:8888，端口不同属于"跨域"请求，
 * 浏览器默认拦截。这里统一对 /api/** 放行，各 Controller 上就不需要再写 @CrossOrigin。
 * </p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 注册跨域映射规则
     *
     * @paramRegistry 由 Spring MVC 提供的跨域注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")                 // 只对后端接口路径生效
                .allowedOriginPatterns("*")            // 允许任意来源（课程演示环境；生产环境应指定具体域名）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的 HTTP 方法
                .allowedHeaders("*")                   // 允许任意请求头
                // 暴露分页总数头：跨域下浏览器默认只放行少数"简单响应头"，
                // 不显式暴露的话前端 JS 读不到 X-Total-Count，分页控件会失效
                .exposedHeaders("X-Total-Count")
                .maxAge(3600);                         // 预检请求缓存 1 小时，减少 OPTIONS 探测
    }
}
