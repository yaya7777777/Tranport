<template>
<div>
 <div class="section-head"><div><h2>运行总览</h2><p>实时查看车辆与运输任务状态</p></div><button class="btn" @click="load">刷新数据</button></div>
 <div v-if="error" class="error">{{error}}</div>
 <div class="stats-grid">
  <div class="stat-card"><span>车辆总数</span><strong>{{stats.vehicleCount}}</strong></div>
  <div class="stat-card"><span>空闲车辆</span><strong>{{stats.idleCount}}</strong></div>
  <div class="stat-card"><span>运输中</span><strong>{{stats.transportingCount}}</strong></div>
  <div class="stat-card"><span>保养中</span><strong>{{stats.maintenanceCount}}</strong></div>
  <div class="stat-card"><span>待处理订单</span><strong>{{stats.pendingOrderCount}}</strong></div>
  <div class="stat-card"><span>执行中调度</span><strong>{{stats.activeDispatchCount}}</strong></div>
 </div>
 <div class="panel"><div class="panel-title">系统数据链路</div><div class="flow">
  <div>Web 前端<br><small>Vue 3</small></div><b>→</b><div>HTTP API<br><small>Axios</small></div><b>→</b><div>后端服务<br><small>Spring Boot</small></div><b>→</b><div>数据库<br><small>MySQL</small></div>
 </div></div>
</div>
</template>
<script setup>
import {reactive,onMounted} from 'vue'
import http from '../api/http'
const stats=reactive({vehicleCount:0,idleCount:0,transportingCount:0,maintenanceCount:0,pendingOrderCount:0,activeDispatchCount:0})
let error=''
async function load(){error='';try{Object.assign(stats,(await http.get('/dashboard/stats')).data)}catch(e){error='无法连接后端，请确认 Spring Boot 已启动且数据库配置正确。'}}
onMounted(load)
</script>
