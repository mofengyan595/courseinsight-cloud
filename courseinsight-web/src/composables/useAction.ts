import { ref } from 'vue'
import { errorMessage } from '../api/http'

export function useAction() {
  const busy = ref(false)
  const error = ref('')
  async function run(action: () => Promise<void>) {
    if (busy.value) return
    busy.value = true
    error.value = ''
    try {
      await action()
    } catch (err) {
      error.value = errorMessage(err)
    } finally {
      busy.value = false
    }
  }
  return { busy, error, run }
}
