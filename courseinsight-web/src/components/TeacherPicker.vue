<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import * as api from '../api'
import { useResource } from '../composables/useResource'
import ErrorNotice from './ErrorNotice.vue'
import PaginationBar from './PaginationBar.vue'
defineProps<{ clearable?: boolean }>()
const model = defineModel<number>()
const emit = defineEmits<{ selected: [name: string] }>()
const keyword = ref('')
const page = ref(1)
const list = useResource(
  (signal) =>
    api.teachers(
      { page: page.value, size: 10, keyword: keyword.value },
      signal,
    ),
  [page, keyword],
)
const selected = useResource(
  (signal) =>
    model.value
      ? api
          .teachers({ page: 1, size: 1, userId: model.value }, signal)
          .then((value) => value.items[0])
      : Promise.resolve(undefined),
  [model],
)
const options = computed(() => {
  const items = list.data.value?.items || []
  const current = selected.data.value
  return current && !items.some((item) => item.id === current.id)
    ? [current, ...items]
    : items
})
watch([selected.loading, selected.data], () => {
  if (
    !selected.loading.value &&
    !selected.error.value &&
    model.value &&
    !selected.data.value
  )
    model.value = undefined
})
function search(value: string) {
  page.value = 1
  keyword.value = value
}
function select(id: number) {
  const teacher = options.value.find((item) => item.id === id)
  if (teacher) emit('selected', teacher.displayName)
}
</script>
<template>
  <div class="full-width">
    <ErrorNotice
      :message="list.error.value || selected.error.value"
      @retry="
        () => {
          list.reload()
          selected.reload()
        }
      "
    />
    <el-select
      v-model="model"
      filterable
      remote
      :remote-method="search"
      :loading="list.loading.value || selected.loading.value"
      :clearable="clearable"
      placeholder="搜索教师姓名或账号"
      aria-label="授课教师账号"
      class="full-width"
      @change="select"
    >
      <el-option
        v-for="item in options"
        :key="item.id"
        :value="item.id"
        :label="`${item.displayName}（${item.username}）`"
      />
    </el-select>
    <PaginationBar
      v-if="list.data.value && list.data.value.totalPages > 1"
      v-model:page="page"
      :size="10"
      :total="list.data.value.total"
    />
  </div>
</template>
