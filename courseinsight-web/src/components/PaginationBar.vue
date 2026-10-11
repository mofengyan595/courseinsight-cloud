<script setup lang="ts">
defineProps<{ page: number; size: number; total: number }>()
defineEmits<{ 'update:page': [value: number] }>()
</script>
<template>
  <div class="pagination-bar">
    <span class="muted">共 {{ total }} 条</span>
    <el-pagination
      :current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      :pager-count="5"
      @current-change="$emit('update:page', $event)"
    />
  </div>
</template>
