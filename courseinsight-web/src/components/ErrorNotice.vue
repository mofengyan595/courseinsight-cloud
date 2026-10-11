<script setup lang="ts">
defineProps<{ message: string; onRetry?: () => unknown }>()
</script>
<template>
  <div v-if="message" class="error-notice" role="alert">
    <span>{{ message }}</span>
    <el-button v-if="onRetry" size="small" @click="onRetry">重新加载</el-button>
  </div>
</template>
