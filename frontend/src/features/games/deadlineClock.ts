export function remainingSeconds(deadline: string, nowMs: number): number {
  const deadlineMs = Date.parse(deadline)
  if (!Number.isFinite(deadlineMs) || !Number.isFinite(nowMs)) return 0
  return Math.max(0, Math.ceil((deadlineMs - nowMs) / 1000))
}
