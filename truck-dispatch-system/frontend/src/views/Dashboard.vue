<template>
<div>
 <div class="section-head"><div><h2>运行总览</h2><p>实时查看车辆六状态分布与运输任务情况</p></div><button class="btn" @click="load">刷新数据</button></div>
 <div v-if="error" class="error">{{error}}</div>

 <!-- 车辆六状态分布（对应 vehicle_status 状态转换规则表） -->
 <div class="stats-grid stats-fit">
  <div class="stat-card"><span>车辆总数</span><strong>{{stats.vehicleCount}}</strong></div>
  <div class="stat-card"><span>空闲 IDLE</span><strong class="c-green">{{stats.idleCount}}</strong></div>
  <div class="stat-card"><span>装载 LOADING</span><strong class="c-orange">{{stats.loadingCount}}</strong></div>
  <div class="stat-card"><span>运输 TRANSPORT</span><strong class="c-blue">{{stats.transportingCount}}</strong></div>
  <div class="stat-card"><span>卸货 UNLOADING</span><strong class="c-purple">{{stats.unloadingCount}}</strong></div>
  <div class="stat-card"><span>加油 REFUEL</span><strong class="c-amber">{{stats.refuelCount}}</strong></div>
  <div class="stat-card"><span>保养 MAINTAIN</span><strong class="c-red">{{stats.maintenanceCount}}</strong></div>
 </div>

 <!-- 业务与基础数据规模 -->
 <div class="stats-grid stats-fit">
  <div class="stat-card"><span>待处理订单</span><strong>{{stats.pendingOrderCount}}</strong></div>
  <div class="stat-card"><span>执行中调度</span><strong>{{stats.activeDispatchCount}}</strong></div>
  <div class="stat-card"><span>POI 站点总数</span><strong>{{stats.poiCount}}</strong><small class="muted">评级参考：500 良 / 1000 优</small></div>
  <div class="stat-card"><span>活跃交通异常</span><strong class="c-red">{{stats.activeAnomalyCount}}</strong></div>
 </div>

 <div class="panel"><div class="panel-title">系统数据链路</div><div class="flow">
  <div>北斗/仿真<br><small>Python · Modbus</small></div><b>→</b><div>HTTP+JSON<br><small>GPS 上报</small></div><b>→</b><div>后端服务<br><small>Spring Boot</small></div><b>→</b><div>数据库<br><small>MySQL</small></div><b>→</b><div>Web 前端<br><small>Vue 3</small></div>
 </div></div>
</div>
</template>
<script setup>
// 运行总览页：轮询驾驶舱聚合统计接口，展示车辆 6 状态 + 订单/调度/POI/异常规模
import {reactive,ref,onMounted} from 'vue'
import http from '../api/http'

const stats = reactive({
  vehicleCount:0, idleCount:0, loadingCount:0, transportingCount:0,
  unloadingCount:0, refuelCount:0, maintenanceCount:0,
  pendingOrderCount:0, activeDispatchCount:0, poiCount:0, activeAnomalyCount:0
})
// 注意：错误提示必须用 ref 才是响应式的（普通 let 变量在模板中不会触发更新）
const error = ref('')

async function load() {
  error.value = ''
  try {
    Object.assign(stats, (await http.get('/dashboard/stats')).data)
  } catch (e) {
    error.value = '无法连接后端，请确认 Spring Boot 已启动（端口 8888）且数据库配置正确。'
  }
}
onMounted(load)
</script>
