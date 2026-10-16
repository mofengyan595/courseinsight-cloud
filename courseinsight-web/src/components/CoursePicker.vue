<script setup lang="ts">
import { computed, ref } from 'vue'
import * as api from '../api'
import { useResource } from '../composables/useResource'
import PaginationBar from './PaginationBar.vue'
import ErrorNotice from './ErrorNotice.vue'
const model = defineModel<number>()
const keyword = ref('')
const search = ref('')
const page = ref(1)
const { data, loading, error, reload } = useResource(
  (signal) =>
    api.courses({ page: page.value, size: 12, keyword: search.value }, signal),
  [page, search],
)
const selected = useResource(
  (signal) =>
    model.value ? api.course(model.value, signal) : Promise.resolve(undefined),
  [model],
)
const options = computed(() => {
  const items = data.value?.items || []
  const current = selected.data.value
  return current && !items.some((item) => item.id === current.id)
    ? [current, ...items]
    : items
})
function find() {
  page.value = 1
  search.value = keyword.value.trim()
  void reload()
}
</script>
<template>
  <div class="course-picker">
    <form class="search-row" @submit.prevent="find">
      <el-input
        v-model="keyword"
        placeholder="搜索课程名称、代码或教师"
        maxlength="100"
        aria-label="搜索课程"
        clearable
      />
      <el-button native-type="submit">搜索</el-button>
    </form>
    <ErrorNotice :message="error" @retry="reload" />
    <ErrorNotice :message="selected.error.value" @retry="selected.reload" />
    <el-skeleton v-if="loading" :rows="2" animated />
    <template v-else-if="data">
      <el-select
        v-model="model"
        placeholder="请选择课程"
        aria-label="选择课程"
        class="full-width"
        clearable
      >
        <el-option
          v-for="item in options"
          :key="item.id"
          :label="`${item.name} · ${item.code} · ${item.teacherName}`"
          :value="item.id"
        />
      </el-select>
      <p v-if="!data.items.length" class="muted">
        没有符合条件的课程，请调整搜索或联系教务管理员。
      </p>
      <PaginationBar
        v-if="data.totalPages > 1"
        v-model:page="page"
        :size="12"
        :total="data.total"
      />
    </template>
    <p class="field-hint">教师只读查看本人课程的评价分析，教务管理员负责课程与批量导入。</p>
  </div>
</template>
