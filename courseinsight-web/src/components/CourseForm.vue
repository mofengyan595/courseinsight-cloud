<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '../api'
import type { Course } from '../types/api'
import { useAction } from '../composables/useAction'
import ErrorNotice from './ErrorNotice.vue'
const props = defineProps<{ course?: Course }>()
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [id: number] }>()
const formRef = ref<FormInstance>()
const form = reactive({
  code: '',
  name: '',
  teacherName: '',
  description: '',
  status: 1,
})
const { busy, error, run } = useAction()
watch(open, (value) => {
  if (value) {
    Object.assign(
      form,
      props.course || {
        code: '',
        name: '',
        teacherName: '',
        description: '',
        status: 1,
      },
    )
    form.description = props.course?.description ?? ''
    error.value = ''
    formRef.value?.clearValidate()
  }
})
const rules: FormRules = {
  code: [
    {
      required: true,
      whitespace: true,
      message: '请输入课程代码',
      trigger: 'blur',
    },
    { max: 32, message: '最多 32 个字符', trigger: 'blur' },
  ],
  name: [
    {
      required: true,
      whitespace: true,
      message: '请输入课程名称',
      trigger: 'blur',
    },
    { max: 100, message: '最多 100 个字符', trigger: 'blur' },
  ],
  teacherName: [
    {
      required: true,
      whitespace: true,
      message: '请输入教师姓名',
      trigger: 'blur',
    },
    { max: 50, message: '最多 50 个字符', trigger: 'blur' },
  ],
  description: [{ max: 500, message: '最多 500 个字符', trigger: 'blur' }],
}
async function save() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  if (props.course && props.course.status !== 0 && form.status === 0) {
    try {
      await ElMessageBox.confirm('确定将课程设为停用？', '停用课程', {
        type: 'warning',
        confirmButtonText: '确定停用',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  await run(async () => {
    const data = {
      code: form.code.trim(),
      name: form.name.trim(),
      teacherName: form.teacherName.trim(),
      description: form.description.trim(),
    }
    const id = props.course
      ? await api.updateCourse(props.course.id, {
          ...data,
          status: form.status,
        })
      : await api.createCourse(data)
    ElMessage.success(props.course ? '课程已更新' : '课程已创建')
    open.value = false
    emit('saved', id)
  })
}
</script>
<template>
  <el-dialog
    v-model="open"
    :title="course ? '编辑课程' : '创建课程'"
    width="540px"
    :close-on-click-modal="!busy"
    :show-close="!busy"
    :close-on-press-escape="!busy"
  >
    <ErrorNotice :message="error" />
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="save"
    >
      <el-form-item label="课程代码" prop="code">
        <el-input v-model="form.code" maxlength="32" placeholder="例如 CS101" />
      </el-form-item>
      <el-form-item label="课程名称" prop="name">
        <el-input v-model="form.name" maxlength="100" />
      </el-form-item>
      <el-form-item label="教师姓名" prop="teacherName">
        <el-input v-model="form.teacherName" maxlength="50" />
      </el-form-item>
      <el-form-item label="课程简介" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
        />
      </el-form-item>
      <el-form-item v-if="course" label="课程状态">
        <el-radio-group v-model="form.status">
          <el-radio :value="1">启用</el-radio>
          <el-radio :value="0">停用</el-radio>
        </el-radio-group>
      </el-form-item>
      <div class="form-actions">
        <el-button :disabled="busy" @click="open = false">取消</el-button>
        <el-button type="primary" :loading="busy" native-type="submit">
          保存课程
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
