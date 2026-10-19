<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSpace,
  NSwitch,
  NText,
  useMessage,
} from 'naive-ui'
import type { DataTableColumn, FormRules } from 'naive-ui'
import { tagApi } from '@/api/tag'
import { errMsg } from '@/api/http'
import type { TagVo } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { h } from 'vue'

const dotCls = ['dot--orange', 'dot--blue', 'dot--purple']

const message = useMessage()
const appStore = useAppStore()

const sections = [
  { type: 0, label: '类型标签', hint: '如 动作 / 科幻 / 悬疑' },
  { type: 1, label: '地区标签', hint: '影片表单「地区」下拉复用本类标签' },
  { type: 2, label: '自定义标签', hint: '运营自由标签' },
] as const

async function load() {
  try {
    await appStore.refreshTags(true)
  } catch (e) {
    message.error(errMsg(e))
  }
}
onMounted(load)

// 行内编辑状态变化时 bump，驱动列表 computed 重算（触发表格重渲染）
const editVersion = ref(0)
const dataLists = sections.map(
  (s) =>
    computed<TagVo[]>(() => {
      void editVersion.value
      return appStore.tags.filter((t) => t.type === s.type)
    }),
)

const byType = (type: number) => appStore.tags.filter((t) => t.type === type)

// ==================== 行内编辑 ====================

const editingId = ref<number | null>(null)
const draft = reactive({ name: '', sortNo: 0 })
const savingId = ref<number | null>(null)

function startEdit(t: TagVo) {
  editingId.value = t.id
  draft.name = t.name
  draft.sortNo = t.sortNo
  editVersion.value++
}

async function saveEdit(t: TagVo) {
  if (!draft.name.trim()) {
    message.warning('标签名不能为空')
    return
  }
  savingId.value = t.id
  try {
    await tagApi.update(t.id, { name: draft.name.trim(), sortNo: draft.sortNo })
    message.success('已保存')
    editingId.value = null
    editVersion.value++
    await appStore.refreshTags(true)
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    savingId.value = null
  }
}

async function toggleStatus(t: TagVo, on: boolean) {
  try {
    await tagApi.update(t.id, { status: on ? 1 : 0 })
    message.success(on ? `已启用「${t.name}」` : `已停用「${t.name}」`)
    await appStore.refreshTags(true)
  } catch (e) {
    message.error(errMsg(e))
  }
}

async function doDelete(t: TagVo) {
  try {
    const r = await tagApi.remove(t.id)
    message.success(
      r.unboundMovies > 0 ? `已删除「${t.name}」，并从 ${r.unboundMovies} 部影片解除绑定` : `已删除「${t.name}」`,
    )
    await appStore.refreshTags(true)
  } catch (e) {
    message.error(errMsg(e))
  }
}

// ==================== 新建 ====================

const addVisible = ref(false)
const addType = ref(0)
const addSaving = ref(false)
const addForm = reactive({ name: '', sortNo: 0 })

const addRules: FormRules = {
  name: [
    { required: true, message: '请输入标签名', trigger: ['input', 'blur'] },
    { max: 32, message: '最多 32 字', trigger: ['input', 'blur'] },
  ],
}

function openAdd(type: number) {
  addType.value = type
  addForm.name = ''
  addForm.sortNo = 0
  addVisible.value = true
}

async function doAdd() {
  if (!addForm.name.trim()) {
    message.warning('请输入标签名')
    return
  }
  addSaving.value = true
  try {
    await tagApi.create({ name: addForm.name.trim(), type: addType.value, sortNo: addForm.sortNo })
    message.success('已创建')
    addVisible.value = false
    await appStore.refreshTags(true)
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    addSaving.value = false
  }
}

// ==================== 列 ====================

function makeColumns(): DataTableColumn<TagVo>[] {
  return [
    {
      title: '标签名',
      key: 'name',
      minWidth: 180,
      render: (t) =>
        editingId.value === t.id
          ? h(NInput, {
              value: draft.name,
              size: 'small',
              maxlength: 32,
              'onUpdate:value': (v: string) => (draft.name = v),
              onFocus: (e: FocusEvent) => (e.target as HTMLInputElement | null)?.select(),
            })
          : h('strong', null, t.name),
    },
    {
      title: '排序',
      key: 'sortNo',
      width: 92,
      render: (t) =>
        editingId.value === t.id
          ? h(NInputNumber, {
              value: draft.sortNo,
              size: 'small',
              'onUpdate:value': (v: number | null) => (draft.sortNo = v ?? 0),
            })
          : t.sortNo,
    },
    { title: '关联影片', key: 'movieCount', width: 96, render: (t) => (t.movieCount ? String(t.movieCount) : '—') },
    {
      title: '状态',
      key: 'status',
      width: 92,
      render: (t) =>
        h(NSwitch, {
          value: t.status === 1,
          onUpdateValue: (v: boolean) => toggleStatus(t, v),
        }),
    },
    {
      title: '操作',
      key: 'ops',
      width: 150,
      render: (t) =>
        h(NSpace, { size: 4 }, {
          default: () =>
            editingId.value === t.id
              ? [
                  h(
                    NButton,
                    { size: 'small', type: 'primary', loading: savingId.value === t.id, onClick: () => saveEdit(t) },
                    { default: () => '保存' },
                  ),
                  h(NButton, { size: 'small', onClick: () => { editingId.value = null; editVersion.value++ } }, { default: () => '取消' }),
                ]
              : [
                  h(NButton, { size: 'small', quaternary: true, onClick: () => startEdit(t) }, { default: () => '编辑' }),
                  h(NPopconfirm, {
                    onPositiveClick: () => doDelete(t),
                    positiveText: '删除',
                    negativeText: '取消',
                  }, {
                    trigger: () =>
                      h(NButton, { size: 'small', quaternary: true, type: 'error' }, { default: () => '删除' }),
                    default: () =>
                      t.movieCount > 0
                        ? `将自动从 ${t.movieCount} 部影片解除绑定，确定删除？`
                        : '确定删除该标签？',
                  }),
                ],
        }),
    },
  ]
}

const columns = makeColumns()
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">标签管理</div>
        <div class="hm-page-sub">类型标签用于影片分类，地区标签同时作为影片「地区」下拉的数据源</div>
      </div>
    </div>

    <div class="hm-stagger">
      <NCard v-for="(s, i) in sections" :key="s.type" :bordered="false" class="sec-card" :style="{ '--i': i } as any">
        <template #header>
          <div class="sec-head">
            <div class="sec-left">
              <span class="dot" :class="dotCls[i]"></span>
              <span class="sec-title">{{ s.label }}</span>
              <NText depth="3" class="sec-hint">{{ s.hint }} · {{ byType(s.type).length }} 个</NText>
            </div>
            <NButton size="small" @click="openAdd(s.type)">＋ 添加</NButton>
          </div>
        </template>
        <NDataTable
          :columns="columns"
          :data="dataLists[i].value"
          :row-key="(t: TagVo) => t.id"
          :bordered="false"
          size="medium"
          :empty-text="'暂无标签'"
        />
      </NCard>
    </div>

    <NModal v-model:show="addVisible" preset="card" :title="`添加${sections.find((x) => x.type === addType)?.label}`" style="width: 440px">
      <NForm :model="addForm" :rules="addRules" label-placement="left" label-width="64">
        <NFormItem label="名称" required>
          <NInput v-model:value="addForm.name" placeholder="标签名（全局唯一）" maxlength="32" />
        </NFormItem>
        <NFormItem label="排序">
          <NInputNumber v-model:value="addForm.sortNo" :min="0" style="width: 120px" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="addVisible = false">取消</NButton>
          <NButton type="primary" :loading="addSaving" @click="doAdd">添加</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.page {
  max-width: none; width: 100%;
}
.sec-card {
  margin-bottom: 16px;
}
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.sec-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex: none;
}
.dot--orange {
  background: var(--hm-primary-grad);
}
.dot--blue {
  background: linear-gradient(135deg, #4f8bff, #2f6fed);
}
.dot--purple {
  background: linear-gradient(135deg, #9a75ff, #6f46ed);
}
.sec-title {
  font-weight: 600;
}
.sec-hint {
  font-size: 12px;
}
</style>
