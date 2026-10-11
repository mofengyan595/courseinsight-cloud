<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
</script>
<template>
  <el-result
    :icon="route.path === '/forbidden' ? 'warning' : 'info'"
    :title="route.path === '/forbidden' ? '权限不足' : '页面不存在'"
    :sub-title="
      route.path === '/forbidden'
        ? '当前角色无法访问此页面。如账号权限已调整，请退出后重新登录。'
        : '请检查链接是否正确。'
    "
  >
    <template #extra>
      <RouterLink to="/courses">
        <el-button type="primary">返回课程列表</el-button>
      </RouterLink>
    </template>
  </el-result>
</template>
