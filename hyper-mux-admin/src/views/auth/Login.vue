<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, useMessage } from 'naive-ui'
import type { FormRules } from 'naive-ui'
import { PlayOutline, ShieldCheckmarkOutline } from '@vicons/ionicons5'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const form = ref({ username: '', password: '' })
const loading = ref(false)

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: ['input', 'blur'] },
    { pattern: /^[a-zA-Z0-9_]{3,32}$/, message: '3~32 位字母/数字/下划线', trigger: ['input', 'blur'] },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: ['input', 'blur'] },
    { len: 6, message: '密码 6~20 位', trigger: ['input', 'blur'] },
  ],
}

async function submit(e?: Event) {
  e?.preventDefault()
  loading.value = true
  try {
    await userStore.login(form.value.username, form.value.password)
    message.success('登录成功')
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.replace(redirect)
  } catch (err: any) {
    message.error(err?.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="blob blob-a"></div>
    <div class="blob blob-b"></div>
    <div class="blob blob-c"></div>
    <div class="grid-overlay"></div>

    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">
          <PlayOutline />
        </div>
        <div>
          <div class="brand-name">HyperMux</div>
          <div class="brand-sub">TV 家庭影院 · 运营控制台</div>
        </div>
      </div>

      <NForm
        :model="form"
        :rules="rules"
        label-placement="top"
        require-trigger="blur"
        class="form"
        @keyup.enter="submit"
      >
        <NFormItem label="用户名" path="username">
          <NInput
            v-model:value="form.username"
            placeholder="请输入用户名"
            clearable
            autofocus
            size="large"
          />
        </NFormItem>
        <NFormItem label="密码" path="password">
          <NInput
            v-model:value="form.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码"
            clearable
            size="large"
          />
        </NFormItem>
        <NButton class="login-btn" size="large" block :loading="loading" @click="submit">
          登 录
        </NButton>
      </NForm>

      <div class="foot">
        <ShieldCheckmarkOutline class="foot-ico" />
        数据链路 AES-GCM 加密 · Token 永不出参
      </div>
    </div>

    <div class="copyright">HyperMux © 2026 · Spring Boot + Naive UI</div>
  </div>
</template>

<style scoped>
.login-wrap {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  overflow: hidden;
  background:
    radial-gradient(1000px 600px at 78% 18%, rgba(245, 130, 13, 0.16), transparent 55%),
    radial-gradient(900px 700px at 12% 88%, rgba(47, 111, 237, 0.14), transparent 55%),
    linear-gradient(158deg, #0e1526 0%, #131b30 48%, #101828 100%);
}

/* 漂浮光斑 */
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  opacity: 0.55;
  pointer-events: none;
}
.blob-a {
  width: 420px;
  height: 420px;
  left: -120px;
  top: -100px;
  background: radial-gradient(circle, rgba(245, 130, 13, 0.5), transparent 65%);
  animation: hm-float-a 16s ease-in-out infinite alternate;
}
.blob-b {
  width: 520px;
  height: 520px;
  right: -160px;
  bottom: -180px;
  background: radial-gradient(circle, rgba(47, 111, 237, 0.42), transparent 65%);
  animation: hm-float-b 19s ease-in-out infinite alternate;
}
.blob-c {
  width: 300px;
  height: 300px;
  right: 20%;
  top: 8%;
  background: radial-gradient(circle, rgba(247, 163, 37, 0.3), transparent 60%);
  animation: hm-float-a 23s ease-in-out infinite alternate-reverse;
}
.grid-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse at center, rgba(0, 0, 0, 0.7), transparent 75%);
}

/* 卡片 */
.login-card {
  position: relative;
  width: 400px;
  max-width: calc(100vw - 48px);
  padding: 38px 36px 26px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow: 0 24px 80px -18px rgba(4, 10, 24, 0.65), 0 4px 18px rgba(4, 10, 24, 0.3);
  animation: hm-scale-in 0.5s cubic-bezier(0.22, 1, 0.36, 1);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 26px;
}
.brand-mark {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  background: linear-gradient(135deg, #f7a325, #f5720d);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 8px 20px -6px rgba(245, 114, 13, 0.65);
}
.brand-mark svg {
  width: 22px;
  height: 22px;
  margin-left: 2px;
}
.brand-name {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 0.04em;
  background: linear-gradient(92deg, #1c2434 30%, #f5820d 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  line-height: 1.2;
}
.brand-sub {
  font-size: 12px;
  color: #98a1b0;
  margin-top: 2px;
}

.form :deep(.n-form-item) {
  margin-bottom: 18px;
}
.form :deep(.n-form-item-label__content) {
  font-size: 13px;
  font-weight: 600;
  color: #5c6675;
}

.login-btn {
  margin-top: 4px;
  height: 44px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.2em;
  background: linear-gradient(135deg, #f7a325 0%, #f5720d 100%) !important;
  border: none !important;
  box-shadow: 0 10px 24px -8px rgba(245, 114, 13, 0.65);
  transition: transform 0.14s ease, box-shadow 0.14s ease, filter 0.14s ease;
}
.login-btn:hover:not(.n-button--disabled) {
  transform: translateY(-2px);
  box-shadow: 0 14px 30px -8px rgba(245, 114, 13, 0.75);
  filter: brightness(1.04);
}
.login-btn:active:not(.n-button--disabled) {
  transform: translateY(0) scale(0.99);
}

.foot {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px dashed #e8ecf3;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  color: #98a1b0;
}
.foot-ico {
  width: 14px;
  height: 14px;
  color: #18a058;
}

.copyright {
  position: absolute;
  bottom: 18px;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.32);
  letter-spacing: 0.04em;
}
</style>
