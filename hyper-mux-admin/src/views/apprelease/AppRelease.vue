<script setup lang="ts">
/**
 * 应用更新（契约 §3.11）：服务器自管 APK。
 * 每个平台（mobile / tv）仅保留最新一条发布，App 内检查更新跟随用户配置的服务器，
 * 自托管部署者独立控制自己的版本。
 */
import AppReleaseCard from '@/components/AppReleaseCard.vue'
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">应用更新</div>
        <div class="hm-page-sub">
          上传 APK 后，App 端「启动检查 + 我的页手动检查」按 versionCode 比较提示更新（24 小时内只主动查一次）。
        </div>
      </div>
    </div>

    <div class="grid">
      <AppReleaseCard platform="mobile" title="Mobile（手机 App）" />
      <AppReleaseCard platform="tv" title="TV（预留，复用同一套接口）" />
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: none;
  width: 100%;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  align-items: start;
}
@media (max-width: 1200px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
