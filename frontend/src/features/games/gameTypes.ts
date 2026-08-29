export type LiarPhase =
  | 'ROLE_REVEAL'
  | 'HINTING'
  | 'DISCUSSING'
  | 'VOTING'
  | 'REVOTING'
  | 'LIAR_GUESSING'
  | 'ROUND_RESULT'
  | 'GAME_RESULT'

export interface LiarPublicHint {
  playerId: string
  text: string
}

export interface LiarPublicHintStatus {
  playerId: string
  status: 'SUBMITTED' | 'SKIPPED'
}

export interface LiarRoundResult {
  winner: string
  invalidated: boolean
  accusedId?: string
  liarGuessedCorrectly: boolean
}

export interface LiarFinalScoreEntry {
  actorId: string
  nickname: string
  score: number
  rank: number
  roundsPlayed: number
}

export interface LiarPublicState {
  gameType: 'LIAR'
  round: number
  phase: LiarPhase
  deadlineAt: string
  currentHinter?: string
  hints: LiarPublicHint[]
  hintStatuses?: LiarPublicHintStatus[]
  submittedPlayerIds: string[]
  revoteCandidates?: string[]
  scores: Record<string, number>
  liarId?: string
  answer?: string
  roundResult?: LiarRoundResult
  finalScores?: LiarFinalScoreEntry[]
}

export interface LiarPrivateState {
  role: 'LIAR' | 'CITIZEN'
  category: string
  word?: string
  hintSubmitted: boolean
  voteSubmitted: boolean
}

export type GamePublicState = LiarPublicState
export type GamePrivateState = LiarPrivateState

export interface GameSnapshot {
  publicState: GamePublicState
  privateState: GamePrivateState | null
}

export type LiarAction =
  | 'HINT_SUBMIT'
  | 'DISCUSSION_END_PROPOSE'
  | 'DISCUSSION_END_VOTE'
  | 'VOTE_SUBMIT'
  | 'REVOTE_SUBMIT'
  | 'LIAR_GUESS_SUBMIT'
  | 'RETURN_TO_WAITING'

type UnknownRecord = Record<string, unknown>

const liarPhases = new Set<LiarPhase>([
  'ROLE_REVEAL', 'HINTING', 'DISCUSSING', 'VOTING', 'REVOTING', 'LIAR_GUESSING', 'ROUND_RESULT', 'GAME_RESULT',
])

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === 'object' && value !== null
}

function validDeadline(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function stringArray(value: unknown): string[] | null {
  return Array.isArray(value) && value.every(item => typeof item === 'string') ? [...value] : null
}

function scores(value: unknown): Record<string, number> | null {
  if (!isRecord(value) || Object.values(value).some(score => typeof score !== 'number' || !Number.isFinite(score))) return null
  return Object.fromEntries(Object.entries(value).map(([actorId, score]) => [actorId, score as number]))
}

function hints(value: unknown): LiarPublicHint[] | null {
  if (!Array.isArray(value)) return null
  const sanitized: LiarPublicHint[] = []
  for (const hint of value) {
    if (!isRecord(hint) || typeof hint.playerId !== 'string' || typeof hint.text !== 'string') return null
    sanitized.push({ playerId: hint.playerId, text: hint.text })
  }
  return sanitized
}

function hintStatuses(value: unknown, publicHints: LiarPublicHint[]): LiarPublicHintStatus[] | null {
  if (value === undefined) return publicHints.map(hint => ({ playerId: hint.playerId, status: 'SUBMITTED' }))
  if (!Array.isArray(value)) return null
  const sanitized: LiarPublicHintStatus[] = []
  for (const status of value) {
    if (!isRecord(status) || typeof status.playerId !== 'string'
      || (status.status !== 'SUBMITTED' && status.status !== 'SKIPPED')) return null
    sanitized.push({ playerId: status.playerId, status: status.status })
  }
  return sanitized
}

function roundResult(value: unknown): LiarRoundResult | null | undefined {
  if (value === undefined) return undefined
  if (!isRecord(value) || typeof value.winner !== 'string' || typeof value.invalidated !== 'boolean'
    || typeof value.liarGuessedCorrectly !== 'boolean') return null
  if (value.accusedId !== undefined && typeof value.accusedId !== 'string') return null
  return {
    winner: value.winner,
    invalidated: value.invalidated,
    ...(typeof value.accusedId === 'string' ? { accusedId: value.accusedId } : {}),
    liarGuessedCorrectly: value.liarGuessedCorrectly,
  }
}

function finalScores(value: unknown): LiarFinalScoreEntry[] | null {
  if (!Array.isArray(value)) return null
  const actorIds = new Set<string>()
  const sanitized: LiarFinalScoreEntry[] = []
  for (const entry of value) {
    if (!isRecord(entry) || typeof entry.actorId !== 'string' || actorIds.has(entry.actorId)
      || typeof entry.nickname !== 'string'
      || typeof entry.score !== 'number' || !Number.isFinite(entry.score)
      || typeof entry.rank !== 'number' || !Number.isSafeInteger(entry.rank) || entry.rank < 1
      || typeof entry.roundsPlayed !== 'number' || !Number.isSafeInteger(entry.roundsPlayed) || entry.roundsPlayed < 0) return null
    actorIds.add(entry.actorId)
    sanitized.push({
      actorId: entry.actorId,
      nickname: entry.nickname,
      score: entry.score,
      rank: entry.rank,
      roundsPlayed: entry.roundsPlayed,
    })
  }
  return sanitized.sort((left, right) => right.score - left.score || left.actorId.localeCompare(right.actorId))
}

export function sanitizeGamePublicState(value: unknown): GamePublicState | null {
  if (!isRecord(value) || value.gameType !== 'LIAR' || !liarPhases.has(value.phase as LiarPhase)
    || typeof value.round !== 'number' || !Number.isSafeInteger(value.round) || value.round < 1
    || !validDeadline(value.deadlineAt)) return null
  const publicHints = hints(value.hints)
  const submittedPlayerIds = stringArray(value.submittedPlayerIds)
  const publicScores = scores(value.scores)
  if (!publicHints || !submittedPlayerIds || !publicScores) return null
  const publicHintStatuses = hintStatuses(value.hintStatuses, publicHints)
  if (!publicHintStatuses) return null
  const phase = value.phase as LiarPhase
  const resultPhase = phase === 'ROUND_RESULT' || phase === 'GAME_RESULT'
  const result = resultPhase ? roundResult(value.roundResult) : undefined
  if (resultPhase && (result === null || result === undefined
    || typeof value.liarId !== 'string' || typeof value.answer !== 'string')) return null
  const revoteCandidates = phase === 'REVOTING' ? stringArray(value.revoteCandidates) : undefined
  if (phase === 'REVOTING' && revoteCandidates === null) return null
  const rankedScores = phase === 'GAME_RESULT' ? finalScores(value.finalScores) : undefined
  if (phase === 'GAME_RESULT' && rankedScores === null) return null
  if (value.currentHinter !== undefined && typeof value.currentHinter !== 'string') return null
  return {
    gameType: 'LIAR',
    round: value.round,
    phase,
    deadlineAt: value.deadlineAt,
    ...(typeof value.currentHinter === 'string' ? { currentHinter: value.currentHinter } : {}),
    hints: publicHints,
    hintStatuses: publicHintStatuses,
    submittedPlayerIds,
    ...(phase === 'REVOTING' ? { revoteCandidates: revoteCandidates ?? [] } : {}),
    scores: publicScores,
    ...(resultPhase ? { liarId: value.liarId as string, answer: value.answer as string, roundResult: result as LiarRoundResult } : {}),
    ...(phase === 'GAME_RESULT' ? { finalScores: rankedScores ?? [] } : {}),
  }
}

export function sanitizeGamePrivateState(value: unknown): GamePrivateState | null {
  if (!isRecord(value) || (value.role !== 'LIAR' && value.role !== 'CITIZEN')
    || typeof value.category !== 'string' || value.category.length === 0
    || typeof value.hintSubmitted !== 'boolean' || typeof value.voteSubmitted !== 'boolean') return null
  if (value.word !== undefined && typeof value.word !== 'string') return null
  return {
    role: value.role,
    category: value.category,
    ...(typeof value.word === 'string' ? { word: value.word } : {}),
    hintSubmitted: value.hintSubmitted,
    voteSubmitted: value.voteSubmitted,
  }
}

export function sanitizeGameSnapshot(value: unknown): GameSnapshot | null {
  if (!isRecord(value)) return null
  const publicState = sanitizeGamePublicState(value.publicState)
  if (!publicState) return null
  if (value.privateState === null || value.privateState === undefined) return { publicState, privateState: null }
  const privateState = sanitizeGamePrivateState(value.privateState)
  return privateState ? { publicState, privateState } : null
}
