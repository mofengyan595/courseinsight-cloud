export function csvError(file?: File): string {
  if (!file || file.size === 0) return '请选择非空的 CSV 文件'
  if (!file.name.toLowerCase().endsWith('.csv')) return '仅支持 .csv 文件'
  if (file.name.length > 255) return 'CSV 文件名不能超过 255 个字符'
  if (file.size > 2 * 1024 * 1024) return 'CSV 文件不能超过 2MB'
  return ''
}
