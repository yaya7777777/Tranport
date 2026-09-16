<template>
<div>
 <div class="section-head"><div><h2>订单调度</h2><p>待处理订单可进行"智能匹配 → 一键派车"</p></div><button class="btn" @click="load">刷新</button></div>
 <div v-if="error" class="error">{{error}}</div>
 <div v-if="message" class="success">{{message}}</div>

 <div class="panel table-panel"><table><thead><tr>
  <th>订单号</th><th>货物</th><th>数量</th><th>起点</th><th>终点</th><th>距离</th>
  <th>车辆</th><th>司机</th><th>订单状态</th><th>调度状态</th><th>操作</th>
 </tr></thead>
 <tbody><tr v-for="o in orders" :key="o.orderId">
  <td class="strong">{{o.orderNo}}</td>
  <td>{{o.cargoName}} <small class="muted">/{{o.cargoType}}</small></td>
  <td>{{o.quantity}}</td>
  <td>{{o.origin}}</td>
  <td>{{o.destination}}</td>
  <td>{{o.distance??'-'}} km</td>
  <td>{{o.plateNumber||'待分配'}}</td>
  <td>{{o.driverName||'待分配'}}</td>
  <td><span :class="['tag',orderClass(o.orderStatus)]">{{orderText(o.orderStatus)}}</span></td>
  <td><span :class="['tag',dispatchClass(o.dispatchStatus)]">{{dispatchText(o.dispatchStatus)}}</span></td>
  <td>
   <!-- 仅待处理订单可以发起智能匹配 -->
   <button v-if="o.orderStatus==='PENDING'" class="btn-mini" @click="openMatch(o)">
     {{selected&&selected.orderId===o.orderId?'收起':'智能匹配'}}
   </button>
   <span v-else class="muted">-</span>
  </td>
 </tr></tbody></table></div>

 <!-- 智能匹配结果面板：候选车辆按综合评分排序 -->
 <div v-if="selected" class="panel match-panel">
  <div class="panel-title">候选车辆评分 — 订单 {{selected.orderNo}}（{{selected.origin}} → {{selected.destination}}）</div>
  <div v-if="matching" class="muted">正在计算匹配结果 ...</div>
  <div v-else-if="matches.length===0" class="muted">暂无可用候选车辆（可能全部车辆正在执行任务）。</div>
  <table v-else class="match-table"><thead><tr>
   <th>评分</th><th>车牌号</th><th>车型</th><th>司机</th><th>当前位置</th>
   <th>到发货地</th><th>剩余油量</th><th>评分依据</th><th>操作</th>
  </tr></thead><tbody>
   <tr v-for="m in matches" :key="m.vehicleId">
    <td><span class="score-badge">{{Math.round(m.score)}}</span></td>
    <td class="strong">{{m.plateNumber}}</td>
    <td>{{m.typeName}}</td>
    <td>{{m.driverName||'未分配'}}</td>
    <td>{{m.currentPoiName||'-'}}</td>
    <td>{{m.distanceToOriginKm==null?'-':m.distanceToOriginKm.toFixed(1)+' km'}}</td>
    <td>{{m.remainingFuel==null?'-':m.remainingFuel.toFixed(0)+' L'}}</td>
    <td class="reason-cell">
      <span v-for="(r,i) in m.reasons" :key="i" class="reason-tag">{{r}}</span>
    </td>
    <td><button class="btn-mini btn-primary" :disabled="dispatching" @click="assign(m)">派车</button></td>
   </tr>
  </tbody></table>
 </div>
</div>
</template>
<script setup>
// 订单调度页：订单列表 + 两级匹配（后端 MatchService 完成车型筛选与车辆评分）
import {ref,onMounted} from 'vue'
import http from '../api/http'

const orders = ref([])
const error = ref('')
const message = ref('')
const selected = ref(null)   // 当前展开匹配面板的订单
const matches = ref([])      // 候选车辆评分列表
const matching = ref(false)
const dispatching = ref(false)

async function load() {
  error.value = ''
  try {
    orders.value = (await http.get('/orders')).data
  } catch (e) {
    error.value = '订单数据加载失败，请检查后端（8888）和 MySQL。'
  }
}

/** 打开/收起某订单的智能匹配面板，并请求候选车辆评分 */
async function openMatch(order) {
  if (selected.value && selected.value.orderId === order.orderId) {
    selected.value = null
    return
  }
  selected.value = order
  matches.value = []
  matching.value = true
  try {
    matches.value = (await http.get('/match/vehicles', {params: {orderId: order.orderId}})).data
  } catch (e) {
    error.value = '智能匹配接口调用失败。'
  } finally {
    matching.value = false
  }
}

/** 把选中订单派给候选车辆，成功后刷新订单列表 */
async function assign(m) {
  dispatching.value = true
  error.value = ''
  message.value = ''
  try {
    const res = (await http.post('/dispatch/assign',
      {orderId: selected.value.orderId, vehicleId: m.vehicleId})).data
    if (res.success) {
      message.value = res.message
      selected.value = null
      await load()
    } else {
      error.value = res.message || '派车失败'
    }
  } catch (e) {
    error.value = '派车请求失败，请检查后端服务。'
  } finally {
    dispatching.value = false
  }
}

const orderText = s => ({PENDING:'待处理',ASSIGNED:'已分配',TRANSPORTING:'运输中',DELIVERED:'已送达',CANCELLED:'已取消'})[s]||s
const dispatchText = s => s ? ({DISPATCHED:'已调度',IN_TRANSIT:'运输中',COMPLETED:'已完成',CANCELLED:'已取消'})[s]||s : '未调度'
const orderClass = s => ({PENDING:'orange',ASSIGNED:'blue',TRANSPORTING:'blue',DELIVERED:'green',CANCELLED:'gray'})[s]||'gray'
const dispatchClass = s => ({DISPATCHED:'orange',IN_TRANSIT:'blue',COMPLETED:'green',CANCELLED:'gray'})[s]||'gray'

onMounted(load)
</script>
