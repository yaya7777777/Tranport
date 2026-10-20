// ============================================================
// Axios 实例：前端所有后端请求的统一入口
// baseURL 指向 Spring Boot 服务（端口必须与后端 server.port 一致 = 8888）
// ============================================================
import axios from 'axios'

export default axios.create({
  baseURL: 'http://localhost:8888/api', // 后端 REST API 根地址
  timeout: 8000                        // 请求超时时间（毫秒）
})
