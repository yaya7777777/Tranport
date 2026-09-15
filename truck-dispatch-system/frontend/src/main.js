import {createApp} from 'vue'
import {createRouter,createWebHistory} from 'vue-router'
import App from './App.vue'
import Dashboard from './views/Dashboard.vue'
import Vehicles from './views/Vehicles.vue'
import Orders from './views/Orders.vue'
import './assets/main.css'
const router=createRouter({history:createWebHistory(),routes:[
 {path:'/',redirect:'/dashboard'},{path:'/dashboard',component:Dashboard},
 {path:'/vehicles',component:Vehicles},{path:'/orders',component:Orders}]})
createApp(App).use(router).mount('#app')
