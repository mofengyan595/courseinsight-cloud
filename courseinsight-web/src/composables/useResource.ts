import { onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { WatchSource } from 'vue'
import axios from 'axios'
import { errorMessage } from '../api/http'

export function useResource<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  sources: WatchSource[] = [],
) {
  const data = shallowRef<T>()
  const loading = ref(false)
  const error = ref('')
  let controller: AbortController | undefined
  function reset() {
    controller?.abort()
    data.value = undefined
    error.value = ''
    loading.value = false
  }
  async function reload() {
    controller?.abort()
    const current = new AbortController()
    controller = current
    loading.value = true
    error.value = ''
    data.value = undefined
    try {
      const result = await fetcher(current.signal)
      if (!current.signal.aborted) data.value = result
    } catch (err) {
      if (!current.signal.aborted && !axios.isCancel(err))
        error.value = errorMessage(err)
    } finally {
      if (!current.signal.aborted) loading.value = false
    }
  }
  watch(
    sources,
    () => {
      void reload()
    },
    { immediate: true },
  )
  onScopeDispose(() => controller?.abort())
  return { data, loading, error, reload, reset }
}
