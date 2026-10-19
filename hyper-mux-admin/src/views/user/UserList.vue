<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NPopconfirm,
  NRadioButton,
  NRadioGroup,
  NSpace,
  NSwitch,
  NTag,
  useDialog,
  useMessage,
} from 'naive-ui'
import type { DataTableColumn } from 'naive-ui'
import { AddOutline, KeyOutline, RefreshOutline, SearchOutline } from '@vicons/ionicons5'
import { userApi, type UserSaveForm } from '@/api/user'
import { errMsg } from '@/api/http'
import type { UserVo } from '@/api/types'
import { useUserStore } from '@/stores/user'
import TimeText from '@/components/TimeText.vue'
import { h } from 'vue'
import { copyText } from '@/utils/format'

const message = useMessage()
const dialog = useDialog()
const userStore = useUserStore()
const selfId = userStore.user?.id

const page = ref(1)
const size = ref(20)
const keyword = ref('')
const roleFilter = ref<number | null>(null)
const statusFilter = ref<number | null>(null)
const list = ref<UserVo[]>([])
const total = ref(0)
const loading = ref(false)

let timer: number | undefined
function onKeyword(v: string) {
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    page.value = 1
    load()
  }, 300)
}

function load() {
  loading.value = true
  userApi
    .page(page.value, size.value, keyword.value.trim() || undefined, roleFilter.value ?? undefined, statusFilter.value ?? undefined)
    .then((r) => {
      list.value = r.list
      total.value = r.total
    })
    .catch((e) => message.error(errMsg(e)))
    .finally(() => (loading.value = false))
}
onMounted(load)

function onFilterChange() {
  page.value = 1
  load()
}

// ==================== 状态开关 / 删除 ====================

async function toggleStatus(u: UserVo, on: boolean) {
  try {
    await userApi.update(u.id, { status: on ? 1 : 0 })
    message.success(on ? `已启用「${u.username}」` : `已停用「${u.username}」`)
    load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : errMsg(e))
  }
}

function askDelete(u: UserVo) {
  dialog.warning({
    title: '删除用户',
    content: `将删除用户「${u.username}」及其全部观看断点，该账号在所有端立即失效，不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const r = await userApi.remove(u.id)
        message.success(`已删除（清断点 ${r.deletedProgress} 条）`)
        load()
      } catch (e) {
        message.error(e instanceof Error ? e.message : errMsg(e))
      }
    },
  })
}

// ==================== 新建 / 编辑 ====================

const USER_RULE = /^[a-zA-Z0-9_]{3,32}$/
const PWD_RULE = /^[a-zA-Z0-9_.]{6,20}$/

const createVisible = ref(false)
const createSaving = ref(false)
const createForm = reactive<UserSaveForm & { nickName: string }>({
  username: '',
  password: '',
  nickName: '',
  role: 0,
})

function openCreate() {
  createForm.username = ''
  createForm.password = ''
  createForm.nickName = ''
  createForm.role = 0
  createVisible.value = true
}

async function doCreate() {
  if (!USER_RULE.test(createForm.username)) {
    message.warning('用户名须 3~32 位字母/数字/下划线')
    return
  }
  if (!PWD_RULE.test(createForm.password)) {
    message.warning('密码须 6~20 位，限字母/数字/_.')
    return
  }
  createSaving.value = true
  try {
    await userApi.create({
      username: createForm.username.trim(),
      password: createForm.password,
      nickName: createForm.nickName.trim() || null,
      role: createForm.role,
    })
    message.success(`用户「${createForm.username}」已创建`)
    createVisible.value = false
    load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : errMsg(e))
  } finally {
    createSaving.value = false
  }
}

const editVisible = ref(false)
const editSaving = ref(false)
const editing = ref<UserVo | null>(null)
const editForm = reactive({ nickName: '', role: 0 })

function openEdit(u: UserVo) {
  editing.value = u
  editForm.nickName = u.nickName ?? ''
  editForm.role = u.role
  editVisible.value = true
}

async function doEdit() {
  const u = editing.value
  if (!u) return
  editSaving.value = true
  try {
    await userApi.update(u.id, {
      nickName: editForm.nickName.trim() || null,
      role: u.id === selfId ? undefined : editForm.role,
    })
    message.success('已保存')
    editVisible.value = false
    load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : errMsg(e))
  } finally {
    editSaving.value = false
  }
}

// ==================== 重置密码 ====================

const resetVisible = ref(false)
const resetSaving = ref(false)
const resetting = ref<UserVo | null>(null)
const resetMode = ref<'random' | 'custom'>('random')
const resetPwd = ref('')

function openReset(u: UserVo) {
  resetting.value = u
  resetMode.value = 'random'
  resetPwd.value = ''
  resetVisible.value = true
}

// 一次性明文回显卡片
const pwdResultVisible = ref(false)
const pwdResult = ref<{ username: string; newPassword: string } | null>(null)

async function doReset() {
  const u = resetting.value
  if (!u) return
  const pwd = resetMode.value === 'custom' ? resetPwd.value : undefined
  if (resetMode.value === 'custom' && !PWD_RULE.test(pwd ?? '')) {
    message.warning('密码须 6~20 位，限字母/数字/_.')
    return
  }
  resetSaving.value = true
  try {
    const r = await userApi.resetPassword(u.id, pwd)
    pwdResult.value = { username: r.username, newPassword: r.newPassword }
    pwdResultVisible.value = true
    resetVisible.value = false
  } catch (e) {
    message.error(e instanceof Error ? e.message : errMsg(e))
  } finally {
    resetSaving.value = false
  }
}

async function copyPwd() {
  if (!pwdResult.value) return
  message.success((await copyText(pwdResult.value.newPassword)) ? '密码已复制' : '复制失败')
}

// ==================== 列 ====================

const columns: DataTableColumn<UserVo>[] = [
  { title: '用户名', key: 'username', minWidth: 130, render: (u) => h('strong', null, u.username) },
  { title: '昵称', key: 'nickName', minWidth: 110, render: (u) => u.nickName || h('span', { class: 'dim' }, '—') },
  {
    title: '角色',
    key: 'role',
    width: 90,
    render: (u) =>
      u.role === 1
        ? h(NTag, { size: 'small', type: 'info' }, { default: () => '管理员' })
        : h(NTag, { size: 'small', bordered: true }, { default: () => '普通用户' }),
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (u) =>
      h(NSwitch, {
        value: u.status === 1,
        disabled: u.id === selfId,
        onUpdateValue: (v: boolean) => toggleStatus(u, v),
      }),
  },
  {
    title: '最近登录',
    key: 'lastLoginTime',
    width: 120,
    render: (u) => h(TimeText, { ms: u.lastLoginTime }),
  },
  { title: '创建时间', key: 'createTime', width: 120, render: (u) => h(TimeText, { ms: u.createTime }) },
  {
    title: '操作',
    key: 'ops',
    width: 190,
    fixed: 'right',
    render: (u) =>
      h(NSpace, { size: 4 }, {
        default: () => [
          h(NButton, { size: 'small', quaternary: true, onClick: () => openEdit(u) }, { default: () => '编辑' }),
          h(
            NButton,
            { size: 'small', quaternary: true, onClick: () => openReset(u) },
            {
              default: () => [
                h(NIcon, { component: KeyOutline, size: 12, style: 'vertical-align: -1px' }),
                ' 重置密码',
              ],
            },
          ),
          ...(u.id === selfId
            ? []
            : [
                h(NPopconfirm, {
                  onPositiveClick: () => askDelete(u),
                  positiveText: '删除',
                  negativeText: '取消',
                }, {
                  trigger: () =>
                    h(NButton, { size: 'small', quaternary: true, type: 'error' }, { default: () => '删除' }),
                  default: () => `删除「${u.username}」？`,
                }),
              ]),
        ],
      }),
  },
]
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">用户管理</div>
        <div class="hm-page-sub">
          普通用户（手机 / TV / Web 多端通用）与管理员账号；停用后该账号立即无法登录，删除会同时清理其观看记录
        </div>
      </div>
    </div>
    <NCard :bordered="false">
    <template #header>
      <div class="card-head">
        <span>账号列表</span>
        <NSpace>
          <NSelect
            v-model:value="roleFilter"
            :options="[
              { label: '全部角色', value: null },
              { label: '普通用户', value: 0 },
              { label: '管理员', value: 1 },
            ]"
            size="small"
            style="width: 120px"
            @update:value="() => onFilterChange()"
          />
          <NSelect
            v-model:value="statusFilter"
            :options="[
              { label: '全部状态', value: null },
              { label: '启用', value: 1 },
              { label: '停用', value: 0 },
            ]"
            size="small"
            style="width: 120px"
            @update:value="() => onFilterChange()"
          />
          <NInput
            v-model:value="keyword"
            placeholder="搜索用户名 / 昵称"
            clearable
            style="width: 200px"
            @update:value="(v: string) => onKeyword(v)"
          >
            <template #prefix><SearchOutline /></template>
          </NInput>
          <NButton @click="load">
            <template #icon><RefreshOutline /></template>
          </NButton>
          <NButton type="primary" @click="openCreate">
            <template #icon><AddOutline /></template>
            新建用户
          </NButton>
        </NSpace>
      </div>
    </template>

    <NDataTable
      :columns="columns"
      :data="list"
      :loading="loading"
      :row-key="(u: UserVo) => u.id"
      :bordered="false"
      size="medium"
      :empty-text="'暂无用户'"
      :pagination="
        {
          page: page,
          pageSize: size,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [20, 50, 100],
          onUpdatePage: (p: number) => (page = p),
          onUpdatePageSize: (s: number) => {
            size = s
            page = 1
          },
        } as any
      "
    />
  </NCard>
  </div>

  <!-- 新建 -->
  <NModal v-model:show="createVisible" preset="card" title="新建用户" style="width: 460px">
    <NForm label-placement="left" label-width="84">
      <NFormItem label="用户名" required>
        <NInput v-model:value="createForm.username" placeholder="3~32 位字母/数字/下划线" maxlength="32" />
      </NFormItem>
      <NFormItem label="初始密码" required>
        <input
          v-model="createForm.password"
          type="password"
          class="pwd-input"
          maxlength="20"
          placeholder="6~20 位，限字母/数字/_. "
          autocomplete="new-password"
        />
        <div class="hint">仅允许字母、数字、下划线、点号</div>
      </NFormItem>
      <NFormItem label="昵称">
        <NInput v-model:value="createForm.nickName" placeholder="可选" maxlength="64" />
      </NFormItem>
      <NFormItem label="角色">
        <NRadioGroup v-model:value="createForm.role">
          <NRadioButton :value="0">普通用户</NRadioButton>
          <NRadioButton :value="1">管理员</NRadioButton>
        </NRadioGroup>
      </NFormItem>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="createVisible = false">取消</NButton>
        <NButton type="primary" :loading="createSaving" @click="doCreate">创建</NButton>
      </NSpace>
    </template>
  </NModal>

  <!-- 编辑 -->
  <NModal v-model:show="editVisible" preset="card" :title="`编辑「${editing?.username}」`" style="width: 420px">
    <NForm label-placement="left" label-width="84">
      <NFormItem label="昵称">
        <NInput v-model:value="editForm.nickName" placeholder="可选" maxlength="64" />
      </NFormItem>
      <NFormItem label="角色">
        <NRadioGroup v-model:value="editForm.role" :disabled="editing?.id === selfId">
          <NRadioButton :value="0">普通用户</NRadioButton>
          <NRadioButton :value="1">管理员</NRadioButton>
        </NRadioGroup>
        <div v-if="editing?.id === selfId" class="hint">不能修改自己的角色（自我保护）</div>
      </NFormItem>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="editVisible = false">取消</NButton>
        <NButton type="primary" :loading="editSaving" @click="doEdit">保存</NButton>
      </NSpace>
    </template>
  </NModal>

  <!-- 重置密码 -->
  <NModal v-model:show="resetVisible" preset="card" :title="`重置「${resetting?.username}」的密码`" style="width: 440px">
    <NSpace vertical :size="12">
      <NRadioGroup v-model:value="resetMode">
        <NRadioButton value="random">随机生成（推荐）</NRadioButton>
        <NRadioButton value="custom">自定义</NRadioButton>
      </NRadioGroup>
      <input
        v-if="resetMode === 'custom'"
        v-model="resetPwd"
        type="password"
        class="pwd-input"
        maxlength="20"
        placeholder="6~20 位，限字母/数字/_. "
        autocomplete="new-password"
      />
    </NSpace>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="resetVisible = false">取消</NButton>
        <NButton type="primary" :loading="resetSaving" @click="doReset">
          <template #icon><KeyOutline /></template>
          重置
        </NButton>
      </NSpace>
    </template>
  </NModal>

  <!-- 一次性明文回显 -->
  <NModal v-model:show="pwdResultVisible" preset="card" title="新密码（一次性）" style="width: 440px">
    <NSpace vertical :size="12">
      <NTag type="warning">关闭后无法再次查看，请妥善转交用户</NTag>
      <NDescriptions :column="1" bordered size="small" label-placement="left">
        <NDescriptionsItem label="用户">{{ pwdResult?.username }}</NDescriptionsItem>
        <NDescriptionsItem label="新密码">
          <NSpace>
            <code class="pwd">{{ pwdResult?.newPassword }}</code>
            <NButton size="small" @click="copyPwd">复制</NButton>
          </NSpace>
        </NDescriptionsItem>
      </NDescriptions>
    </NSpace>
    <template #footer>
      <NButton type="primary" @click="pwdResultVisible = false">我已保存，关闭</NButton>
    </template>
  </NModal>
</template>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.dim {
  color: #999;
}
.hint {
  margin-top: 6px;
  color: #999;
  font-size: 12px;
}
.pwd-input {
  width: 100%;
  box-sizing: border-box;
  padding: 7px 12px;
  border: 1px solid #e0e0e6;
  border-radius: 10px;
  font-size: 14px;
  outline: none;
  background: #fff;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}
.pwd-input:focus {
  border-color: #f5820d;
  box-shadow: 0 0 0 3px rgba(245, 130, 13, 0.12);
}
.pwd {
  font-family: Consolas, Menlo, monospace;
  font-size: 14px;
  background: #f5f5f5;
  padding: 2px 8px;
  border-radius: 4px;
}
</style>
