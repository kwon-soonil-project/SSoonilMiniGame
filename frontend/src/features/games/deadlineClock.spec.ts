import { describe, expect, it } from 'vitest'
import { remainingSeconds } from './deadlineClock'

describe('remainingSeconds', () => {
  it('rounds a server deadline up without changing phase', () => {
    expect(remainingSeconds('2026-08-24T00:00:01.100Z', Date.parse('2026-08-24T00:00:00Z'))).toBe(2)
  })

  it('returns zero at and after the server deadline, including a client clock ahead of the server', () => {
    const deadline = '2026-08-24T00:00:01.000Z'
    expect(remainingSeconds(deadline, Date.parse('2026-08-24T00:00:01.000Z'))).toBe(0)
    expect(remainingSeconds(deadline, Date.parse('2026-08-24T00:00:30.000Z'))).toBe(0)
  })

  it('uses the supplied current time rather than the browser clock', () => {
    const deadline = '2026-08-24T00:00:10.000Z'
    expect(remainingSeconds(deadline, Date.parse('2026-08-24T00:00:08.001Z'))).toBe(2)
    expect(remainingSeconds(deadline, Date.parse('2026-08-24T00:00:05.001Z'))).toBe(5)
  })
})
