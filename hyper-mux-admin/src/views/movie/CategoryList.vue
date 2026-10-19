<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NIcon,
  NImage,
  NInput,
  NInputNumber,
  NModal,
  NPagination,
  NPopconfirm,
  NSpace,
  NSpin,
  NSwitch,
  NTag,
  useDialog,
  useMessage,
} from 'naive-ui'
import type { FormRules } from 'naive-ui'
import { imgUrl } from '@/utils/image'
import { AddOutline, AlbumsOutline, RefreshOutline, SearchOutline } from '@vicons/ionicons5'
import { categoryApi } from '@/api/category'
import { errMsg, ApiError } from '@/api/http'
import type { CategoryVo } from '@/api/types'
import { useAppStore } from '@/stores/app'
import EmptyState from '@/components/EmptyState.vue'

const message = useMessage()
const dialog = useDialog()
const appStore = useAppStore()

const page = ref(1)
const size = ref(12)
const keyword = ref('')
const list = ref<CategoryVo[]>([])
const total = ref(0)
const loading = ref(false)

let timer: number | undefined
onMounted(() => {
  void appStore.refreshCategories().catch(() => {})
  load()
})

function keywordDebounced(k: string) {
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    page.value = 1
    load()
  }, 300)
}

async function load() {
  loading.value = true
  try {
    const r = await categoryApi.page(page.value, size.value, keyword.value.trim() || undefined)
    list.value = r.list
    total.value = r.total
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

// ==================== 新建 / 编辑 ====================

const modalVisible = ref(false)
const editing = ref<CategoryVo | null>(null)
const saving = ref(false)
const form = reactive({ name: '', coverUrl: '', description: '', sortNo: 0 })

const rules: FormRules = {
  name: [
    { required: true, message: '请输入类型名称', trigger: ['input', 'blur'] },
    { max: 64, message: '最多 64 字', trigger: ['input', 'blur'] },
  ],
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.coverUrl = ''
  form.description = ''
  form.sortNo = 0
  modalVisible.value = true
}

function openEdit(r: CategoryVo) {
  editing.value = r
  form.name = r.name
  form.coverUrl = r.coverUrl ?? ''
  form.description = r.description ?? ''
  form.sortNo = r.sortNo ?? 0
  modalVisible.value = true
}

async function saveCategory() {
  if (!form.name.trim()) {
    message.warning('请输入类型名称')
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name.trim(),
      coverUrl: form.coverUrl.trim() || null,
      description: form.description.trim() || null,
      sortNo: form.sortNo,
    }
    if (editing.value) {
      await categoryApi.update(editing.value.id, body)
      message.success('已保存')
    } else {
      await categoryApi.create(body)
      message.success('已创建')
    }
    modalVisible.value = false
    await Promise.all([load(), appStore.refreshCategories(true).catch(() => {})])
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    saving.value = false
  }
}

// ==================== 状态 / 删除 ====================

async function toggleStatus(r: CategoryVo, on: boolean) {
  try {
    await categoryApi.update(r.id, { status: on ? 1 : 0 })
    message.success(on ? `已启用「${r.name}」` : `已停用「${r.name}」`)
    await Promise.all([load(), appStore.refreshCategories(true).catch(() => {})])
  } catch (e) {
    message.error(errMsg(e))
  }
}

async function doDelete(r: CategoryVo) {
  try {
    await categoryApi.remove(r.id)
    message.success(`已删除「${r.name}」`)
    await Promise.all([load(), appStore.refreshCategories(true).catch(() => {})])
  } catch (e) {
    if (e instanceof ApiError && e.code === 409) {
      const n: number = e.data?.movieCount ?? r.movieCount
      dialog.warning({
        title: '无法删除',
        content: `「${r.name}」下还有 ${n} 部影片，请先移走或删除这些影片。`,
        positiveText: '知道了',
      })
    } else {
      message.error(errMsg(e))
    }
  }
}
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">影视类型</div>
        <div class="hm-page-sub">如电视剧、电影、综艺、动漫……可按需自定义；TV 端首页按类型分区，停用后该类型影片对 TV 不可见</div>
      </div>
      <NSpace>
        <NInput
          v-model:value="keyword"
          placeholder="搜索类型名称"
          clearable
          style="width: 200px"
          @update:value="(v: string) => keywordDebounced(v)"
        >
          <template #prefix><NIcon><SearchOutline /></NIcon></template>
        </NInput>
        <NButton @click="load">
          <template #icon><RefreshOutline /></template>
          刷新
        </NButton>
        <NButton type="primary" @click="openCreate">
          <template #icon><AddOutline /></template>
          新建类型
        </NButton>
      </NSpace>
    </div>

    <NSpin :show="loading">
      <div v-if="total > 0 || loading" class="grid hm-stagger">
        <div
          v-for="(c, i) in list"
          :key="c.id"
          class="hm-card hm-card--lift cat-card"
          :class="{ disabled: c.status !== 1 }"
          :style="{ '--i': i } as any"
        >
          <div class="cover">
            <NImage
              v-if="c.coverUrl"
              :src="imgUrl(c.coverUrl)"
              width="100%"
              height="128"
              object-fit="cover"
              class="cover-img"
            />
            <div v-else class="cover-fake">
              <NIcon size="34"><AlbumsOutline /></NIcon>
            </div>
            <NTag class="cover-status" :type="c.status === 1 ? 'success' : 'default'" size="small" :bordered="false">
              {{ c.status === 1 ? '启用' : '停用' }}
            </NTag>
          </div>

          <div class="body">
            <div class="name">{{ c.name }}</div>
            <div class="desc">{{ c.description || '暂无简介' }}</div>
            <div class="foot" @click.stop>
              <div class="foot-left">
                <NTag size="small" type="info" :bordered="false">{{ c.movieCount }} 部影片</NTag>
                <span class="dim sort">排序 {{ c.sortNo ?? 0 }}</span>
              </div>
              <div class="foot-right">
                <NSwitch
                  :value="c.status === 1"
                  size="small"
                  @update:value="(v: boolean) => toggleStatus(c, v)"
                />
                <NButton size="small" quaternary @click="openEdit(c)">编辑</NButton>
                <NPopconfirm
                  :on-positive-click="() => doDelete(c)"
                  positive-text="删除"
                  negative-text="取消"
                >
                  <template #trigger>
                    <NButton size="small" quaternary type="error">删除</NButton>
                  </template>
                  删除类型「{{ c.name }}」？（有影片时禁止删除）
                </NPopconfirm>
              </div>
            </div>
          </div>
        </div>
      </div>
      <EmptyState v-else-if="!loading" desc="还没有影视类型，先建一个「电视剧 / 电影 / 综艺」吧">
        <template #action>
          <NButton type="primary" @click="openCreate">
            <template #icon><AddOutline /></template>
            新建第一个类型
          </NButton>
        </template>
      </EmptyState>
    </NSpin>

    <div v-if="total > size" class="pager">
      <NPagination
        :page="page"
        :page-size="size"
        :item-count="total"
        show-size-picker
        :page-sizes="[12, 24, 48]"
        @update:page="(p: number) => (page = p)"
        @update:page-size="(s: number) => { size = s; page = 1 }"
      />
    </div>
  </div>

  <NModal
    v-model:show="modalVisible"
    preset="card"
    :title="editing ? `编辑类型「${editing.name}」` : '新建影视类型'"
    style="width: 520px"
  >
    <NForm :model="form" :rules="rules" label-placement="left" label-width="72">
      <NFormItem label="名称" path="name" required>
        <NInput v-model:value="form.name" placeholder="如：电视剧 / 电影 / 综艺 / 动漫" maxlength="64" />
      </NFormItem>
      <NFormItem label="封面" path="coverUrl">
        <NInput v-model:value="form.coverUrl" placeholder="可选，http(s):// 封面图" />
      </NFormItem>
      <NFormItem label="简介" path="description">
        <NInput
          v-model:value="form.description"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="可选，TV 端类型分区副标题"
          maxlength="512"
        />
      </NFormItem>
      <NFormItem label="排序" path="sortNo">
        <NInputNumber v-model:value="form.sortNo" :min="0" style="width: 120px" />
        <span class="hint" style="display: inline; margin-left: 10px">数字越小越靠前</span>
      </NFormItem>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="modalVisible = false">取消</NButton>
        <NButton type="primary" :loading="saving" @click="saveCategory">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 16px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

.cat-card {
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.cat-card.disabled .cover {
  filter: grayscale(0.9);
  opacity: 0.75;
}
.cover {
  position: relative;
  height: 128px;
  background: #eef1f6;
  overflow: hidden;
}
.cover :deep(.n-image) {
  width: 100%;
  height: 128px;
}
.cover :deep(img) {
  width: 100%;
  height: 128px;
  object-fit: cover;
  display: block;
}
.cover-fake {
  width: 100%;
  height: 128px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #b6bfce;
  background:
    radial-gradient(240px 90px at 80% 0%, rgba(245, 130, 13, 0.08), transparent 70%),
    linear-gradient(135deg, #f6f8fb, #edf0f6);
}
.cover-status {
  position: absolute;
  top: 10px;
  right: 10px;
  box-shadow: 0 2px 8px rgba(4, 10, 24, 0.25);
}

.body {
  padding: 14px 16px 12px;
  display: flex;
  flex-direction: column;
  flex: 1;
}
.name {
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.desc {
  color: var(--hm-ink-3);
  font-size: 12px;
  margin-top: 4px;
  min-height: 32px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.6;
}
.foot {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--hm-line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.foot-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.foot-right {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}
.sort {
  font-size: 12px;
  white-space: nowrap;
}
.dim {
  color: var(--hm-ink-3);
}
</style>
