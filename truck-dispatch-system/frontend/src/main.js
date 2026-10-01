// ============================================================
// 前端路由表：5 个业务页面
//   /dashboard   运行总览（车辆状态/订单/POI 统计）
//   /vehicles    车辆台账
//   /orders      订单调度（含智能匹配派车面板）
//   /simulation  仿真沙盘（POI/车辆/路线/交通异常 SVG 可视化）
//   /master      基础数据（车型/货物分类/匹配矩阵/POI 分类统计）
//   /gps         设备定位（北斗真实上报与仿真轨迹）
// ============================================================
import {createApp} from 'vue'
import {createRouter,createWebHistory} from 'vue-router'
import App from './App.vue'
import Dashboard from './views/Dashboard.vue'
import Vehicles from './views/Vehicles.vue'
import Orders from './views/Orders.vue'
import Simulation from './views/Simulation.vue'
import MasterData from './views/MasterData.vue'
import GpsMonitor from './views/GpsMonitor.vue'
import './assets/main.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {path: '/', redirect: '/dashboard'},
    {path: '/dashboard', component: Dashboard},
    {path: '/vehicles', component: Vehicles},
    {path: '/orders', component: Orders},
    {path: '/simulation', component: Simulation},
    {path: '/master', component: MasterData},
    {path: '/gps', component: GpsMonitor}
  ]
})
createApp(App).use(router).mount('#app')
