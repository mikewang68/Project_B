<template>
  <main class="sso-entry">
    <h1>能源管控系统</h1>
    <p role="status">{{ message }}</p>
    <el-alert v-if="failed" title="统一登录未完成，请重新从已配置的统一入口发起，或使用原生验证码登录。" type="warning" :closable="false" />
    <el-button @click="nativeLogin">原生验证码登录</el-button>
  </main>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { exchangeSsoTicket } from '@/api/sso'
import useUserStore from '@/store/modules/user'
import { safeSsoDestination, validPortalOrigin, verifiedTicketMessage } from '@/utils/ssoPolicy'

const route = useRoute()
const router = useRouter()
const message = ref('正在等待统一入口确认身份…')
const failed = ref(false)
const origin = validPortalOrigin(import.meta.env.VITE_EMCS_SSO_PORTAL_ORIGIN || '')
const destination = safeSsoDestination(route.query.redirect)
const source = window.opener || (window.parent !== window ? window.parent : null)
const nonce = Array.from(crypto.getRandomValues(new Uint8Array(24)), byte => byte.toString(16).padStart(2, '0')).join('')
let accepting = true
let timer

function fail() { failed.value = true; accepting = false; message.value = '无法完成可信身份兑换。' }
function nativeLogin() { router.replace({ path: '/login', query: { redirect: destination } }) }
async function receive(event) {
  if (!accepting) return
  const ticket = verifiedTicketMessage(event, { origin, source, nonce })
  if (!ticket) return
  accepting = false
  clearTimeout(timer)
  message.value = '正在验证一次性票据…'
  try {
    const result = await exchangeSsoTicket(ticket)
    const session = result?.data
    if (typeof session?.token !== 'string' || !session.token.startsWith('emcs_sso_')) throw new Error('Invalid session')
    useUserStore().acceptSso(session)
    // A full reload discards routes and state belonging to a previously logged-in user.
    window.location.replace(import.meta.env.BASE_URL + destination.slice(1))
  } catch { fail() }
}
onMounted(() => {
  if (!origin || !source || route.query.ticket || window.location.hash) { fail(); return }
  window.addEventListener('message', receive)
  source.postMessage({ type: 'EMCS_SSO_READY', nonce }, origin)
  timer = setTimeout(fail, 60000)
})
onBeforeUnmount(() => { accepting = false; clearTimeout(timer); window.removeEventListener('message', receive) })
</script>

<style scoped>
.sso-entry { max-width: 620px; margin: 12vh auto; padding: 32px; }
.sso-entry h1 { font-size: 28px; margin-bottom: 24px; }
.sso-entry p { margin-bottom: 24px; }
.sso-entry .el-button { margin-top: 24px; }
</style>
