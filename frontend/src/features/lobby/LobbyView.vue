<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useAuthStore } from '../auth/authStore'
import CreateRoomDialog from './CreateRoomDialog.vue'
import RoomCard from './RoomCard.vue'
import { useLobbyStore, type CreatedRoom } from './lobbyStore'

const auth = useAuthStore()
const lobby = useLobbyStore()
const createDialogOpen = ref(false)
const roomCode = ref('')
const codeError = ref<string | null>(null)

onMounted(() => {
  void lobby.initialize()
})
onUnmounted(() => lobby.dispose())

function enterByCode(): void {
  const normalized = roomCode.value.trim()
  if (!/^\d{6}$/.test(normalized)) {
    codeError.value = '6자리 방 코드를 입력해 주세요.'
    return
  }
  codeError.value = null
  window.location.assign(`/rooms/${normalized}`)
}

function openCreateDialog(): void {
  createDialogOpen.value = true
}

function closeCreateDialog(): void {
  createDialogOpen.value = false
  window.setTimeout(() => { document.getElementById('create-room-button')?.focus() }, 0)
}

function moveToCreatedRoom(room: CreatedRoom): void {
  createDialogOpen.value = false
  window.location.assign(`/rooms/${room.code}`)
}
</script>

<template>
  <div class="lobby-shell">
    <header class="lobby-header">
      <a class="brand" href="/lobby" aria-label="미니게임 놀이터 로비">
        <span aria-hidden="true">◈</span> 미니게임 놀이터
      </a>
      <p v-if="auth.actor" class="actor"><strong>{{ auth.actor.nickname }}</strong>님, 반가워요!</p>
    </header>

    <main>
      <section class="welcome" aria-labelledby="lobby-title">
        <div>
          <p class="eyebrow">PUBLIC LOBBY</p>
          <h1 id="lobby-title">오늘은 어떤 게임을<br><span>함께 해볼까요?</span></h1>
          <p>입장 가능한 방을 찾거나 친구들을 위한 새 방을 만들어 보세요.</p>
        </div>
        <button id="create-room-button" class="create-button" data-action="create-room" type="button" @click="openCreateDialog">
          <span aria-hidden="true">+</span> 방 만들기
        </button>
      </section>

      <section class="quick-entry" aria-labelledby="quick-entry-title">
        <div>
          <h2 id="quick-entry-title">방 코드로 바로 입장</h2>
          <p>친구에게 받은 6자리 코드가 있나요?</p>
        </div>
        <form @submit.prevent="enterByCode">
          <label class="sr-only" for="room-code">방 코드</label>
          <input id="room-code" v-model="roomCode" inputmode="numeric" maxlength="6" placeholder="방 코드 6자리" :aria-invalid="Boolean(codeError)" :aria-describedby="codeError ? 'room-code-error' : undefined">
          <button type="submit">입장</button>
          <p v-if="codeError" id="room-code-error" role="alert">{{ codeError }}</p>
        </form>
      </section>

      <section class="room-browser" aria-labelledby="rooms-title">
        <div class="section-title">
          <div><p class="eyebrow">FIND A GAME</p><h2 id="rooms-title">공개 방 찾기</h2></div>
          <span class="room-count">{{ lobby.rooms.length }}개의 방</span>
        </div>

        <form class="filters" @submit.prevent="lobby.loadRooms">
          <div class="search-field">
            <label class="sr-only" for="room-search">방 제목 검색</label>
            <input id="room-search" v-model="lobby.filters.query" type="search" placeholder="방 제목으로 검색">
          </div>
          <div class="game-filter">
            <label class="sr-only" for="game-filter">게임 종류</label>
            <select id="game-filter" v-model="lobby.filters.gameType">
              <option value="">모든 게임</option>
              <option value="LIAR">라이어</option>
              <option value="DRAWING">그림 퀴즈</option>
              <option value="CHOSUNG">초성 퀴즈</option>
              <option value="MAJORITY">다수결 예측</option>
            </select>
          </div>
          <label class="available-filter" for="available-filter">
            <input id="available-filter" v-model="lobby.filters.available" type="checkbox">
            입장 가능한 방만
          </label>
          <button class="filter-submit" type="submit">검색</button>
        </form>

        <p v-if="lobby.error" class="state-message error" role="alert">
          {{ lobby.error }} <button type="button" @click="lobby.loadRooms">다시 시도</button>
        </p>
        <p v-else-if="lobby.loading" class="state-message" aria-live="polite">방 목록을 불러오는 중…</p>
        <div v-else-if="lobby.rooms.length" class="room-grid">
          <RoomCard v-for="room in lobby.rooms" :key="room.roomId" :room="room" />
        </div>
        <div v-else class="empty-state">
          <span aria-hidden="true">🎮</span>
          <h3>조건에 맞는 방이 없어요</h3>
          <p>새 방을 만들어 첫 번째 방장이 되어보세요.</p>
        </div>
      </section>
    </main>

    <CreateRoomDialog :open="createDialogOpen" @close="closeCreateDialog" @created="moveToCreatedRoom" />
  </div>
</template>

<style scoped>
.lobby-shell { min-height: 100vh; background: #f8f8fc; color: #252735; }
.lobby-header { height: 4.6rem; display: flex; align-items: center; justify-content: space-between; padding: 0 max(1.25rem, calc((100vw - 1160px) / 2)); background: white; border-bottom: 1px solid #ececf2; }
.brand { color: #2b2d3b; font-weight: 900; text-decoration: none; }.brand span { color: #604bd5; }
.actor { margin: 0; color: #666a7b; font-size: .9rem; }
main { width: min(calc(100% - 2.5rem), 1160px); margin: auto; padding: 3.5rem 0 5rem; }
.welcome { display: flex; justify-content: space-between; align-items: end; gap: 2rem; }
.eyebrow { margin: 0; color: #6652d9; font-size: .72rem; font-weight: 900; letter-spacing: .14em; }
.welcome h1 { margin: .6rem 0 1rem; font-size: clamp(2.25rem, 5vw, 4rem); line-height: 1.08; letter-spacing: -.05em; }.welcome h1 span { color: #5b47d6; }.welcome p:last-child { color: #6c7080; }
.create-button { flex: none; border: 0; border-radius: .9rem; background: #5b47d6; color: white; cursor: pointer; font: inherit; font-weight: 850; padding: 1rem 1.25rem; box-shadow: 0 10px 24px rgb(91 71 214 / 22%); }
.quick-entry { display: flex; align-items: center; justify-content: space-between; gap: 2rem; margin: 3rem 0 4rem; border-radius: 1.25rem; padding: 1.4rem 1.6rem; background: #27243d; color: white; }.quick-entry h2 { margin: 0 0 .3rem; font-size: 1rem; }.quick-entry p { margin: 0; color: #b9b7c8; font-size: .83rem; }.quick-entry form { display: grid; grid-template-columns: minmax(10rem, 15rem) auto; }.quick-entry input { border: 0; border-radius: .75rem 0 0 .75rem; font: inherit; padding: .8rem; }.quick-entry button { border: 0; border-radius: 0 .75rem .75rem 0; background: #7662e7; color: white; cursor: pointer; font-weight: 800; padding: 0 1.1rem; }.quick-entry form p { grid-column: 1 / -1; margin-top: .4rem; color: #ffb4ab; }
.section-title { display: flex; justify-content: space-between; align-items: end; margin-bottom: 1.2rem; }.section-title h2 { margin: .3rem 0 0; font-size: 1.7rem; }.room-count { color: #747888; font-size: .85rem; }
.filters { display: grid; grid-template-columns: minmax(12rem, 1fr) 11rem auto auto; gap: .7rem; margin-bottom: 1.4rem; }.filters input[type="search"], .filters select { box-sizing: border-box; width: 100%; border: 1px solid #dcdde6; border-radius: .75rem; background: white; font: inherit; padding: .75rem; }.available-filter { display: flex; align-items: center; gap: .4rem; padding: 0 .4rem; font-size: .85rem; font-weight: 700; }.filter-submit { border: 0; border-radius: .75rem; background: #eae7ff; color: #4f3ac7; cursor: pointer; font-weight: 800; padding: 0 1rem; }
.room-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1rem; }.state-message, .empty-state { border: 1px dashed #d7d5e5; border-radius: 1rem; padding: 3rem; text-align: center; color: #6d7181; }.state-message.error { color: #9b2c24; }.state-message button { border: 0; background: transparent; color: #533ec7; font-weight: 800; text-decoration: underline; }.empty-state span { font-size: 2rem; }.empty-state h3 { color: #343746; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
button:focus-visible, input:focus-visible, select:focus-visible, a:focus-visible { outline: 3px solid #ad9fff; outline-offset: 2px; }
@media (max-width: 900px) { .room-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.filters { grid-template-columns: 1fr 10rem; }.available-filter, .filter-submit { min-height: 2.7rem; } }
@media (max-width: 767px) { .lobby-header { height: 4rem; }.actor { display: none; } main { width: min(calc(100% - 1.5rem), 1160px); padding-top: 2rem; }.welcome { align-items: start; flex-direction: column; }.welcome br { display: none; }.create-button { width: 100%; }.quick-entry { align-items: stretch; flex-direction: column; margin: 2rem 0 3rem; }.quick-entry form { grid-template-columns: 1fr auto; }.filters { grid-template-columns: 1fr 1fr; }.search-field { grid-column: 1 / -1; }.room-grid { grid-template-columns: 1fr; gap: .65rem; } }
</style>
