<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { remainingSeconds } from '../deadlineClock'
import type { GamePrivateState, GamePublicState, LiarAction } from '../gameTypes'
import type { RoomParticipant } from '../../room/roomStore'
import DiscussionPanel from './DiscussionPanel.vue'
import HintPanel from './HintPanel.vue'
import LiarResultPanel from './LiarResultPanel.vue'
import RoleRevealPanel from './RoleRevealPanel.vue'
import VotePanel from './VotePanel.vue'

const props = defineProps<{ publicState: GamePublicState; privateState: GamePrivateState | null; participants: RoomParticipant[]; actorId: string; connected: boolean }>()
const emit = defineEmits<{ action: [payload: { action: LiarAction; data: Record<string, unknown> }] }>()
const nowMs = ref(Date.now())
const clock = window.setInterval(() => { nowMs.value = Date.now() }, 1000)
onUnmounted(() => window.clearInterval(clock))

const phaseLabel = computed(() => ({ ROLE_REVEAL: '역할 공개', HINTING: '힌트 제출', DISCUSSING: '토론', VOTING: '투표', REVOTING: '재투표', LIAR_GUESSING: '라이어의 제시어 추측', ROUND_RESULT: '라운드 결과', GAME_RESULT: '게임 결과' }[props.publicState.phase]))
const remaining = computed(() => remainingSeconds(props.publicState.deadlineAt, nowMs.value))
const currentHinterName = computed(() => props.participants.find(participant => participant.actorId === props.publicState.currentHinter)?.nickname ?? '다른 참가자')
const hintDisabled = computed(() => !props.connected || props.publicState.currentHinter !== props.actorId || props.privateState?.hintSubmitted === true)
const voteDisabled = computed(() => !props.connected || props.privateState?.voteSubmitted === true)
const actionDisabled = computed(() => !props.connected)
const submitted = computed(() => props.publicState.submittedPlayerIds.includes(props.actorId))
const discussionDisabled = computed(() => actionDisabled.value || submitted.value)
const guessDisabled = computed(() => actionDisabled.value || submitted.value || props.privateState?.role !== 'LIAR')

function send(action: LiarAction, data: Record<string, unknown> = {}): void { if (props.connected) emit('action', { action, data }) }

function submitGuess(event: Event): void {
  const form = event.currentTarget as HTMLFormElement
  const word = new FormData(form).get('word')
  if (typeof word === 'string' && word.trim()) send('LIAR_GUESS_SUBMIT', { word: word.trim() })
}
</script>

<template>
  <div class="liar-game" data-region="liar-game">
    <header class="game-status"><div><p class="eyebrow">ROUND {{ publicState.round }}</p><h1>라이어 게임</h1></div><div><p data-region="phase-announcement" aria-live="polite">{{ phaseLabel }}</p><time data-region="timer" :datetime="publicState.deadlineAt">{{ remaining }}초 남음</time></div></header>
    <RoleRevealPanel :private-state="privateState" />
    <HintPanel v-if="publicState.phase === 'HINTING'" :disabled="hintDisabled" :current-hinter-name="currentHinterName" @submit="send('HINT_SUBMIT', { text: $event })" />
    <DiscussionPanel v-else-if="publicState.phase === 'DISCUSSING'" :disabled="discussionDisabled" @propose="send('DISCUSSION_END_PROPOSE')" @approve="send('DISCUSSION_END_VOTE')" />
    <VotePanel v-else-if="publicState.phase === 'VOTING' || publicState.phase === 'REVOTING'" :participants="participants" :actor-id="actorId" :disabled="voteDisabled" :revote="publicState.phase === 'REVOTING'" @submit="send(publicState.phase === 'REVOTING' ? 'REVOTE_SUBMIT' : 'VOTE_SUBMIT', { targetId: $event })" />
    <section v-else-if="publicState.phase === 'LIAR_GUESSING'" class="panel" aria-labelledby="guess-title"><p class="eyebrow">LIAR GUESS</p><h2 id="guess-title">제시어 추측</h2><form @submit.prevent="submitGuess"><label for="liar-guess">제시어</label><input id="liar-guess" name="word" maxlength="100" :disabled="guessDisabled"><button type="submit" aria-label="제시어 추측" :disabled="guessDisabled">제시어 추측</button></form></section>
    <LiarResultPanel v-else-if="publicState.phase === 'ROUND_RESULT' || publicState.phase === 'GAME_RESULT'" :result="publicState.roundResult" :final="publicState.phase === 'GAME_RESULT'" :disabled="actionDisabled" @return-to-waiting="send('RETURN_TO_WAITING')" />
    <section v-else class="panel"><p>역할을 확인하고 다음 단계를 기다려 주세요.</p></section>
  </div>
</template>

<style scoped>
.liar-game { display: grid; gap: 1rem; }.game-status { display: flex; align-items: end; justify-content: space-between; gap: 1rem; }.game-status h1 { margin: 0; font-size: clamp(1.45rem, 3vw, 2.2rem); }.game-status > div:last-child { text-align: right; }.game-status > div:last-child p { margin: 0; color: #5843cf; font-weight: 850; }.game-status time { color: #686b7a; font-size: .8rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }.panel { border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }.panel form { display: grid; gap: .55rem; margin-top: 1rem; }.panel label { font-size: .78rem; font-weight: 800; }.panel input { border: 1px solid #dcdce6; border-radius: .7rem; padding: .7rem; }.panel button { border: 0; border-radius: .7rem; background: #5b47d6; color: white; cursor: pointer; font-weight: 850; padding: .7rem; }.panel button:disabled, .panel input:disabled { cursor: not-allowed; opacity: .55; }@media (max-width: 767px) { .game-status { align-items: start; flex-direction: column; }.game-status > div:last-child { text-align: left; } }
</style>
