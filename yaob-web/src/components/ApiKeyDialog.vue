<template>
  <el-dialog v-model="visible" title="设置币安 API 密钥" width="450px" @close="handleClose">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="API Key" prop="apiKey">
        <el-input v-model="form.apiKey" placeholder="输入币安 API Key" />
      </el-form-item>
      <el-form-item label="API Secret" prop="apiSecret">
        <el-input v-model="form.apiSecret" placeholder="输入币安 API Secret" type="password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleClear" type="danger" plain>清除密钥</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { setApiKeys, clearApiKeys } from '@/api/trade'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, (v) => { visible.value = v })
watch(visible, (v) => emit('update:modelValue', v))

const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({ apiKey: '', apiSecret: '' })
const rules: FormRules = {
  apiKey: [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
  apiSecret: [{ required: true, message: '请输入 API Secret', trigger: 'blur' }],
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await setApiKeys(form.apiKey, form.apiSecret)
    ElMessage.success('API 密钥已保存')
    visible.value = false
    emit('saved')
  } catch { /* handled */ } finally {
    saving.value = false
  }
}

async function handleClear() {
  try {
    await ElMessageBox.confirm('确定要清除 API 密钥吗？清除后将无法自动交易。', '警告', { type: 'warning' })
    await clearApiKeys()
    ElMessage.success('API 密钥已清除')
    visible.value = false
    emit('saved')
  } catch { /* cancelled or error */ }
}

function handleClose() {
  form.apiKey = ''
  form.apiSecret = ''
}
</script>
