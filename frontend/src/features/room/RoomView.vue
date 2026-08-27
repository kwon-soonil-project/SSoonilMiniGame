<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { restoreFocus, trapDialogFocus } from '../../shared/ui/dialogFocus'
import { useAuthStore } from '../auth/authStore'
import GameShell from '../games/GameShell.vue'
import type { LiarAction } from '../games/gameTypes'
import ParticipantList from './ParticipantList.vue'
import RoomChat, { type RoomInputSubmission } from './RoomChat.vue'
import RoomSettingsPanel from './RoomSettingsPanel.vue'
import { useRoomStore } from './roomStore'

const props = withDefaults(defineProps<{ code?: string }>(), { code: '' })
const auth = useAuthStore()
const room = useRoomStore()
const password = ref('')
const mobileChatButton = ref<HTMLElement | null>(null)
const mobileChatSheet = ref<HTMLElement | null>(null)

const code = computed(() => props.code || window.location.pathname.split('/').filter(Boolean).at(-1) || '')
const isHost = computed(() => room.snapshot?.hostId === auth.actor?.actorId)
const me = computed(() => room.snapshot?.participants.find(participant => participant.actorId === auth.actor?.actorId))
const canCommand = computed(() => room.connection === 'connected')
const isPlaying = computed(() => room.snapshot?.status === 'PLAYING' && room.snapshot.game !== null)
const isWaiting = computed(() => room.snapshot?.status === 'WAITING')
const roomTransitionFailed = computed(() => room.connection === 'failed' && room.snapshot?.code !== code.value)
const gameLabel = computed(() => ({ LIAR: '라이어 게임', DRAWING: '그림 퀴즈', CHOSUNG: '초성 퀴즈', MAJORITY: '다수결 예측' }[room.snapshot?.gameType ?? 'LIAR']))

watch(code, nextCode => {
  password.value = ''
  if (room.snapshot?.code !== nextCode) void enter(nextCode, '')
}, { immediate: true })
onUnmounted(() => room.clearRoom())

async function enter(roomCode: string, value: string): Promise<void> {
  try { await room.join(roomCode, value) } catch { /* Store exposes a stable inline error. */ }
}

function submitPassword(): void {
  void enter(code.value, password.value).then(() => { password.value = '' })
}

function handleInput(submission: RoomInputSubmission): void {
  if (submission.mode === 'ANSWER') room.sendAnswer(submission.body)
  else room.sendChat(submission.body)
}

function handleGameAction(payload: { action: LiarAction; data: Record<string, unknown> }): void {
  room.sendGameAction(payload.action, payload.data)
}

function leaveRoom(): void {
  void room.leave().then(() => window.location.assign('/lobby'))
}

async function openMobileChat(): Promise<void> {
  room.openChat()
  await nextTick()
  mobileChatSheet.value?.querySelector<HTMLInputElement>('input')?.focus()
}

function closeMobileChat(): void {
  room.closeChat()
  void nextTick(() => restoreFocus(mobileChatButton.value))
}

function handleMobileChatKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMobileChat()
    return
  }
  trapDialogFocus(event, mobileChatSheet.value)
}
</script>

<template>
  <div class="room-shell">
    <header class="room-header">
      <a href="/lobby" class="brand"><span aria-hidden="true">◈</span> 미니게임 놀이터</a>
      <div class="room-code"><span>방 코드</span><strong>{{ code }}</strong></div>
      <button type="button" class="leave" @click="leaveRoom">나가기</button>
    </header>

    <main v-if="room.snapshot" class="room-main">
      <section class="hero" aria-labelledby="room-title">
        <div>
          <p class="eyebrow">{{ isPlaying ? 'NOW PLAYING' : isWaiting ? 'WAITING ROOM' : 'ROOM CLOSED' }} · {{ gameLabel }}</p>
          <h1 id="room-title">{{ room.snapshot.title }}</h1>
          <p>{{ isPlaying ? '현재 라운드와 남은 시간을 확인해 주세요.' : isWaiting ? '친구들이 모이면 준비 상태를 확인하고 게임을 시작하세요.' : '이 방은 종료되었습니다.' }}</p>
        </div>
        <div class="connection" :class="room.connection" role="status" aria-live="polite">
          <span aria-hidden="true">●</span>
          {{ room.connection === 'connected' ? '실시간 연결됨' : room.connection === 'connecting' ? '연결 중' : room.connection === 'reconnecting' ? '다시 연결 중' : '연결 실패' }}
        </div>
      </section>

      <p v-if="room.error" class="notice error" role="alert">
        {{ room.error }}
        <button v-if="roomTransitionFailed" data-action="retry-room-transition" type="button" @click="enter(code, '')">방 이동 다시 시도</button>
        <button v-else-if="room.connection === 'failed'" data-action="retry-room-recovery" type="button" @click="room.retryRecovery()">방 상태 다시 동기화</button>
      </p>
      <p v-if="room.commandError" class="notice error" role="alert">{{ room.commandError }}</p>

      <GameShell
        v-if="isPlaying && room.snapshot.game"
        :public-state="room.snapshot.game.publicState"
        :private-state="room.snapshot.game.privateState"
        :participants="room.snapshot.participants"
        :actor-id="auth.actor?.actorId ?? ''"
        :connected="canCommand"
        @action="handleGameAction"
      >
        <template #sidebar>
          <ParticipantList :participants="room.snapshot.participants" :host-id="room.snapshot.hostId" :scores="room.snapshot.game.publicState.scores" />
          <RoomChat class="desktop-chat" :messages="room.chats" :disabled="!canCommand" @submit="handleInput" />
        </template>
      </GameShell>

      <section v-else-if="isWaiting" class="game-focus" aria-labelledby="current-game-title">
        <div><p class="eyebrow">NEXT GAME</p><h2 id="current-game-title">{{ gameLabel }}</h2><p>{{ room.snapshot.rounds }}라운드 · 행동 {{ room.snapshot.actionSeconds }}초 · 토론 {{ room.snapshot.discussionSeconds }}초</p></div>
        <button v-if="isHost" data-action="start-game" type="button" :disabled="!canCommand || !room.snapshot.canStart" @click="room.startGame">게임 시작</button>
        <button v-else data-action="ready" type="button" :disabled="!canCommand || me?.spectator" @click="room.sendReady(!me?.ready)">
          {{ me?.ready ? '준비 취소' : '준비하기' }}
        </button>
      </section>

      <div v-if="isWaiting" class="room-grid">
        <ParticipantList :participants="room.snapshot.participants" :host-id="room.snapshot.hostId" />
        <RoomSettingsPanel :settings="room.snapshot" :editable="isHost && canCommand" :read-only-label="isHost ? '연결 복구 후 변경 가능' : '방장만 변경'" @save="room.updateSettings" />
        <RoomChat class="desktop-chat" :messages="room.chats" :disabled="!canCommand" @submit="handleInput" />
      </div>
      <RoomSettingsPanel v-else-if="isPlaying" class="playing-settings" :settings="room.snapshot" :editable="false" read-only-label="게임 중에는 설정을 변경할 수 없어요" />
      <section v-else class="room-closed" role="status">이 방은 종료되었습니다.</section>
    </main>

    <main v-else class="entry-state">
      <div v-if="room.loading" role="status">방에 입장하는 중…</div>
      <form v-else-if="room.passwordRequired" @submit.prevent="submitPassword">
        <p class="eyebrow">PRIVATE ENTRY</p><h1>비밀번호가 필요한 방이에요</h1>
        <label for="room-password">방 비밀번호</label>
        <input id="room-password" v-model="password" type="password" maxlength="20" autocomplete="current-password" required>
        <button type="submit">입장</button>
        <p v-if="room.error" role="alert">{{ room.error }}</p>
      </form>
      <div v-else>
        <h1>방에 입장하지 못했습니다</h1><p role="alert">{{ room.error }}</p><button type="button" @click="enter(code, '')">다시 시도</button>
      </div>
    </main>

    <button v-if="room.snapshot" ref="mobileChatButton" class="mobile-chat-button" data-action="open-mobile-chat" type="button" @click="openMobileChat">
      채팅 <span v-if="room.unreadChatCount" aria-label="읽지 않은 메시지">{{ room.unreadChatCount }}</span>
    </button>
    <div v-if="room.snapshot && room.chatOpen" ref="mobileChatSheet" class="mobile-chat-sheet" role="dialog" aria-modal="true" aria-label="방 채팅" @keydown="handleMobileChatKeydown">
      <button class="sheet-close" type="button" aria-label="채팅 닫기" @click="closeMobileChat">×</button>
      <RoomChat :messages="room.chats" :disabled="!canCommand" @submit="handleInput" />
    </div>
  </div>
</template>

<style scoped>
.room-shell { min-height: 100vh; background: #f8f8fc; color: #282a39; }.room-header { height: 4.4rem; display: flex; align-items: center; gap: 1.5rem; padding: 0 max(1rem, calc((100vw - 1180px) / 2)); border-bottom: 1px solid #e9e8f0; background: white; }.brand { margin-right: auto; color: #2b2d3b; font-weight: 900; text-decoration: none; }.brand span { color: #604bd5; }.room-code { display: flex; align-items: center; gap: .45rem; color: #777a89; font-size: .75rem; }.room-code strong { border-radius: .5rem; background: #eeebff; color: #503bc6; letter-spacing: .12em; padding: .4rem .55rem; }.leave { border: 0; background: transparent; color: #777a89; cursor: pointer; font-weight: 750; }
.room-main { width: min(calc(100% - 2rem), 1180px); margin: auto; padding: 2.5rem 0 4rem; }.hero { display: flex; align-items: end; justify-content: space-between; gap: 2rem; }.eyebrow { margin: 0 0 .4rem; color: #6652d9; font-size: .7rem; font-weight: 900; letter-spacing: .12em; }.hero h1 { margin: 0; font-size: clamp(1.8rem, 4vw, 3rem); letter-spacing: -.04em; }.hero p:last-child { color: #727586; }.connection { color: #6d7080; font-size: .78rem; font-weight: 850; white-space: nowrap; }.connection.connected { color: #16834b; }.connection.failed { color: #a33a31; }
.notice { border-radius: .7rem; padding: .75rem 1rem; }.notice.error { background: #fff0ee; color: #9b2c24; }.notice button { margin-left: .5rem; border: 0; background: transparent; color: #513cc6; cursor: pointer; font-weight: 850; text-decoration: underline; }.game-focus { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin: 2rem 0 1rem; border-radius: 1.1rem; background: #2b2843; color: white; padding: 1.35rem 1.5rem; }.game-focus h2 { margin: 0; }.game-focus p:last-child { margin-bottom: 0; color: #bbb8ce; font-size: .82rem; }.game-focus button { flex: none; min-width: 8rem; border: 0; border-radius: .8rem; background: #7562e5; color: white; cursor: pointer; font-weight: 900; padding: .9rem 1rem; }
.room-grid { display: grid; grid-template-columns: minmax(12rem, .85fr) minmax(17rem, 1.15fr) minmax(17rem, 1.2fr); gap: 1rem; min-height: 31rem; }.mobile-chat-button, .mobile-chat-sheet { display: none; }
.entry-state { display: grid; min-height: calc(100vh - 4.4rem); place-items: center; padding: 1rem; text-align: center; }.entry-state form, .entry-state > div { width: min(100%, 24rem); border: 1px solid #e4e3ec; border-radius: 1rem; background: white; box-shadow: 0 12px 30px rgb(35 31 66 / 8%); padding: 1.5rem; }.entry-state label { display: block; margin: 1rem 0 .35rem; text-align: left; font-size: .8rem; font-weight: 800; }.entry-state input { box-sizing: border-box; width: 100%; border: 1px solid #d9d9e3; border-radius: .7rem; padding: .75rem; }.entry-state button { width: 100%; margin-top: .7rem; border: 0; border-radius: .7rem; background: #5b47d6; color: white; cursor: pointer; font-weight: 850; padding: .8rem; }
button:focus-visible, input:focus-visible, select:focus-visible, a:focus-visible { outline: 3px solid #ad9fff; outline-offset: 2px; }
@media (max-width: 900px) { .room-grid { grid-template-columns: .85fr 1.15fr; }.desktop-chat { grid-column: 1 / -1; min-height: 24rem; } }
@media (max-width: 767px) { .room-header { height: 4rem; }.room-code span { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }.room-main { width: min(calc(100% - 1.25rem), 1180px); padding: 1.5rem 0 6rem; }.hero { align-items: start; flex-direction: column; gap: .5rem; }.hero p:last-child { display: none; }.game-focus { align-items: stretch; flex-direction: column; margin-top: 1.25rem; }.game-focus button { width: 100%; }.room-grid { grid-template-columns: 1fr; min-height: 0; }.room-grid [data-region="settings"] { order: -1; }.desktop-chat { display: none; }.mobile-chat-button { position: fixed; z-index: 10; right: 1rem; bottom: 1rem; display: block; border: 0; border-radius: 999px; background: #5b47d6; color: white; box-shadow: 0 8px 24px rgb(50 37 130 / 28%); cursor: pointer; font-weight: 900; padding: .9rem 1.15rem; }.mobile-chat-button span { display: inline-grid; min-width: 1.25rem; height: 1.25rem; margin-left: .35rem; place-items: center; border-radius: 999px; background: #ffdd68; color: #403500; font-size: .7rem; }.mobile-chat-sheet { position: fixed; z-index: 20; inset: 18vh 0 0; display: block; border-radius: 1.2rem 1.2rem 0 0; background: white; box-shadow: 0 -12px 40px rgb(35 31 66 / 18%); padding: 2.2rem .65rem .65rem; }.mobile-chat-sheet [data-region="chat"] { height: calc(82vh - 3rem); border: 0; padding: .5rem; }.sheet-close { position: absolute; top: .55rem; right: 1rem; border: 0; background: transparent; color: #555866; cursor: pointer; font-size: 1.5rem; } }
</style>
