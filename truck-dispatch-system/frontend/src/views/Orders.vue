<template>
<div>
 <div class="section-head"><div><h2>订单调度</h2><p>订单与车辆、司机、路线的联动查询</p></div><button class="btn" @click="load">刷新</button></div>
 <div v-if="error" class="error">{{error}}</div>
 <div class="panel table-panel"><table><thead><tr><th>订单号</th><th>货物</th><th>数量</th><th>起点</th><th>终点</th><th>距离</th><th>车辆</th><th>司机</th><th>订单状态</th><th>调度状态</th></tr></thead>
 <tbody><tr v-for="o in orders" :key="o.orderId"><td class="strong">{{o.orderNo}}</td><td>{{o.cargoName}} <small class="muted">/{{o.cargoType}}</small></td><td>{{o.quantity}}</td><td>{{o.origin}}</td><td>{{o.destination}}</td><td>{{o.distance??'-'}} km</td><td>{{o.plateNumber||'待分配'}}</td><td>{{o.driverName||'待分配'}}</td><td><span class="tag blue">{{orderText(o.orderStatus)}}</span></td><td><span class="tag orange">{{dispatchText(o.dispatchStatus)}}</span></td></tr></tbody></table></div>
</div>
</template>
<script setup>
import {ref,onMounted} from 'vue';import http from '../api/http'
const orders=ref([]);let error=''
async function load(){try{orders.value=(await http.get('/orders')).data}catch(e){error='订单数据加载失败，请检查后端和 MySQL。'}}
const orderText=s=>({PENDING:'待处理',ASSIGNED:'已分配',TRANSPORTING:'运输中',DELIVERED:'已送达',CANCELLED:'已取消'})[s]||s
const dispatchText=s=>({DISPATCHED:'已调度',IN_TRANSIT:'运输中',COMPLETED:'已完成',CANCELLED:'已取消'})[s]||s||'未调度'
onMounted(load)
</script>
