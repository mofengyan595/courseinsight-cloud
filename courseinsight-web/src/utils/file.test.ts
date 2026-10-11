import { describe, expect, it } from 'vitest'
import { csvError } from './file'
describe('文件选择校验', () => {
  it('拒绝空文件与错误扩展名', () => {
    expect(csvError()).toContain('非空')
    expect(csvError(new File([], 'empty.csv'))).toContain('非空')
    expect(csvError(new File(['data'], 'data.txt'))).toContain('.csv')
  })
  it('拒绝超限文件，允许常规 CSV', () => {
    expect(
      csvError(new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'big.csv')),
    ).toContain('2MB')
    expect(csvError(new File(['content,rating\n测试,5'], 'valid.CSV'))).toBe('')
  })
})
