<template>
<div>
 <div class="section-head"><div><h2>车辆管理</h2><p>数据直接来自 MySQL vehicle 表</p></div><button class="btn" @click="load">刷新</button></div>
 <div v-if="error" class="error">{{error}}</div>
 <div class="panel table-panel"><table><thead><tr><th>ID</th><th>车牌号</th><th>车型</th><th>司机</th><th>当前位置</th><th>状态</th><th>剩余油量</th><th>可运行里程</th></tr></thead>
 <tbody><tr v-for="v in vehicles" :key="v.vehicleId"><td>{{v.vehicleId}}</td><td class="strong">{{v.plateNumber}}</td><td>{{v.vehicleType}}</td><td>{{v.driverName||'未分配'}}</td><td>{{v.currentLocation||'-'}}</td><td><span :class="['tag',tagClass(v.currentStatus)]">{{statusText(v.currentStatus)}}</span></td><td>{{v.remainingFuel??'-'}} L</td><td>{{v.estimatedRange??'-'}} km</td></tr></tbody></table></div>
</div>
</template>
<script setup>
import {ref,onMounted} from 'vue';import http from '../api/http'
// 车辆台账：直接查询 vehicle 关联车型/POI 的视图接口
const vehicles=ref([])
// 错误提示必须使用 ref 才具备响应式
const error=ref('')
async function load(){
  error.value=''
  try{vehicles.value=(await http.get('/vehicles')).data}
  catch(e){error.value='车辆数据加载失败，请检查后端（8888）和 MySQL。'}
}
// 车辆六状态中文映射
function statusText(s){return {IDLE:'空闲',LOADING:'装载',UNLOADING:'卸货',TRANSPORT:'运输',REFUEL:'加油',MAINTAIN:'保养'}[s]||s}
// 各状态标签配色
function tagClass(s){return {IDLE:'green',TRANSPORT:'blue',LOADING:'orange',UNLOADING:'purple',REFUEL:'amber',MAINTAIN:'red'}[s]||'gray'}
onMounted(load)
</script>
