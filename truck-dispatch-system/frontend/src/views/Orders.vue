<template>
<div>
 <div class="section-head"><div><h2>订单调度</h2><p>新增需求 · 智能匹配派车 · 失效（软删除） · 收益测算</p></div>
  <div class="head-actions">
   <button class="btn" @click="toggleCreate">新增需求</button>
   <button class="btn" @click="load">刷新</button>
  </div>
 </div>
 <div v-if="error" class="error">{{error}}</div>
 <div v-if="message" class="success">{{message}}</div>

 <!-- 新增需求表单：基于厂仓关系生成 PENDING 订单 -->
 <div v-if="showCreate" class="panel form-panel">
  <div class="panel-title">新增需求（选择厂仓关系自动确定起终点）</div>
  <div class="form-grid">
   <div class="form-item"><label>厂仓关系（起点 → 终点）</label>
    <select v-model="form.fwKey">
     <option v-for="(r,i) in options.relations" :key="i" :value="i">
      {{r.factoryName}} → {{r.warehouseName}}（{{relationText(r.relationType)}}）
     </option>
    </select></div>
   <div class="form-item"><label>货物</label>
    <select v-model="form.cargoId">
     <option v-for="c in options.cargos" :key="c.cargoId" :value="c.cargoId">{{c.cargoName}}</option>
    </select></div>
   <div class="form-item"><label>数量（件/吨）</label><input v-model.number="form.quantity" type="number" min="0.1" step="0.1"></div>
   <div class="form-item"><label>优先级</label>
    <select v-model.number="form.priority">
     <option :value="1">高</option><option :value="2">中</option><option :value="3">低</option>
    </select></div>
   <div class="form-item"><label>备注</label><input v-model="form.remark" placeholder="选填"></div>
  </div>
  <div class="form-actions">
   <button class="btn btn-go" :disabled="creating" @click="createOrder">提交需求</button>
   <button class="btn" @click="showCreate=false">取消</button>
  </div>
 </div>

 <!-- 收益测算结果面板 -->
 <div v-if="revenue" class="panel form-panel">
  <div class="panel-title">运输收益 — 订单 {{revenueOrderId}}</div>
  <div class="revenue-box">
   <span>预期收入 <b class="c-green">{{revenue.income.toFixed(2)}}</b> 元</span>
   <span>运输成本 <b class="c-orange">{{revenue.cost.toFixed(2)}}</b> 元</span>
   <span>毛收益 <b :class="revenue.profit>=0?'c-green':'c-red'">{{revenue.profit.toFixed(2)}}</b> 元</span>
  </div>
  <small class="muted">{{revenue.detail}}</small>
 </div>

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
  <td class="op-cell">
   <!-- 待处理订单可智能匹配；进行中订单可失效（软删除）；所有订单可测算收益 -->
   <button v-if="o.orderStatus==='PENDING'" class="btn-mini" @click="openMatch(o)">
     {{selected&&selected.orderId===o.orderId?'收起':'智能匹配'}}
   </button>
   <button v-if="['PENDING','ASSIGNED','TRANSPORTING'].includes(o.orderStatus)"
     class="btn-mini btn-mini-danger" @click="disableOrder(o)">失效</button>
   <button class="btn-mini" @click="showRevenue(o)">收益</button>
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
// 订单调度页：新增需求（厂仓关系）+ 两级匹配派车 + 软删除失效 + 收益测算
import {ref,reactive,onMounted} from 'vue'
import http from '../api/http'

const orders = ref([])
const error = ref('')
const message = ref('')
const selected = ref(null)   // 当前展开匹配面板的订单
const matches = ref([])      // 候选车辆评分列表
const matching = ref(false)
const dispatching = ref(false)
const showCreate = ref(false)
const creating = ref(false)
const options = ref({relations: [], cargos: []})
const revenue = ref(null)        // 收益测算结果
const revenueOrderId = ref(null)

async function load() {
  error.value = ''
  try {
    orders.value = (await http.get('/orders')).data
  } catch (e) {
    error.value = '订单数据加载失败，请检查后端（8888）和 MySQL。'
  }
}

/** 展开新增需求表单并拉取下拉数据（厂仓关系/货物） */
async function toggleCreate() {
  showCreate.value = !showCreate.value
  revenue.value = null
  if (showCreate.value && options.value.relations.length === 0) {
    try {
      // axios 响应 .data = ApiResult 包装，表单数据在其 .data 字段
      options.value = (await http.get('/orders/options')).data.data
      if (options.value.relations.length > 0) form.fwKey = 0
      if (options.value.cargos.length > 0) form.cargoId = options.value.cargos[0].cargoId
    } catch (e) {
      error.value = '下拉数据加载失败，请检查后端服务。'
    }
  }
}

const form = reactive({fwKey: 0, cargoId: null, quantity: 1, priority: 2, remark: ''})

/** 提交新增需求：POST /api/orders */
async function createOrder() {
  const rel = options.value.relations[form.fwKey]
  if (!rel || !form.cargoId) {
    error.value = '请选择厂仓关系与货物。'
    return
  }
  creating.value = true
  error.value = ''
  message.value = ''
  try {
    const res = (await http.post('/orders', {
      factoryId: rel.factoryId, warehouseId: rel.warehouseId, cargoId: form.cargoId,
      quantity: form.quantity, priority: form.priority, remark: form.remark
    })).data
    if (res.success) {
      message.value = res.message
      showCreate.value = false
      await load()
    } else {
      error.value = res.message || '创建失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '创建请求失败，请检查后端服务。'
  } finally {
    creating.value = false
  }
}

/** 失效需求（软删除），已派车订单会级联取消调度并释放车辆 */
async function disableOrder(order) {
  if (!confirm(`确认失效订单 ${order.orderNo} 吗？（软删除，可追溯）`)) return
  error.value = ''
  message.value = ''
  try {
    const res = (await http.post(`/orders/${order.orderId}/disable`)).data
    if (res.success) {
      message.value = res.message
      await load()
    } else {
      error.value = res.message || '失效失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '失效请求失败。'
  }
}

/** 收益测算：GET /api/orders/{id}/revenue */
async function showRevenue(order) {
  error.value = ''
  try {
    const res = (await http.get(`/orders/${order.orderId}/revenue`)).data
    if (res.success) {
      revenue.value = res.data
      revenueOrderId.value = order.orderNo
      showCreate.value = false
      window.scrollTo({top: 0, behavior: 'smooth'})
    } else {
      error.value = res.message || '收益计算失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '收益计算请求失败。'
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

const relationText = t => ({PROCURE:'采购',PRODUCE:'生产',SALE:'销售'})[t]||t
const orderText = s => ({PENDING:'待处理',ASSIGNED:'已分配',TRANSPORTING:'运输中',DELIVERED:'已送达',CANCELLED:'已取消'})[s]||s
const dispatchText = s => s ? ({DISPATCHED:'已调度',IN_TRANSIT:'运输中',COMPLETED:'已完成',CANCELLED:'已取消'})[s]||s : '未调度'
const orderClass = s => ({PENDING:'orange',ASSIGNED:'blue',TRANSPORTING:'blue',DELIVERED:'green',CANCELLED:'gray'})[s]||'gray'
const dispatchClass = s => ({DISPATCHED:'orange',IN_TRANSIT:'blue',COMPLETED:'green',CANCELLED:'gray'})[s]||'gray'

onMounted(load)
</script>
<style scoped>
.op-cell{display:flex;gap:6px}
</style>
