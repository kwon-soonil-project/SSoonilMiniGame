<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useLobbyStore, type CreatedRoom, type GameType } from './lobbyStore'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  close: []
  created: [room: CreatedRoom]
}>()
const lobby = useLobbyStore()
const title = ref('')
const isPublic = ref(true)
const passwordEnabled = ref(false)
const password = ref('')
const gameType = ref<GameType>('LIAR')
const error = ref<string | null>(null)
const titleInput = ref<HTMLInputElement | null>(null)

watch(() => props.open, async (open) => {
  if (!open) return
  error.value = null
  await nextTick()
  titleInput.value?.focus()
})

function close(): void {
  if (!lobby.creating) emit('close')
}

async function submit(): Promise<void> {
  const normalizedTitle = title.value.trim()
  if (!normalizedTitle || Array.from(normalizedTitle).length > 24) {
    error.value = '방 제목은 1~24자로 입력해 주세요.'
    return
  }
  const normalizedPassword = password.value.trim()
  if (passwordEnabled.value && (!normalizedPassword || normalizedPassword.length > 20)) {
    error.value = '비밀번호는 1~20자로 입력해 주세요.'
    return
  }
  error.value = null
  try {
    const created = await lobby.createRoom({
      title: normalizedTitle,
      visibility: isPublic.value ? 'PUBLIC' : 'PRIVATE',
      ...(passwordEnabled.value ? { password: normalizedPassword } : {}),
      gameType: gameType.value,
    })
    emit('created', created)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '방을 만들지 못했습니다.'
  }
}
</script>

<template>
  <div
    v-if="open"
    class="dialog-backdrop"
    role="dialog"
    aria-modal="true"
    aria-labelledby="create-room-title"
    @keydown.esc="close"
    @click.self="close"
  >
    <form class="dialog-card" @submit.prevent="submit">
      <button class="close-button" type="button" aria-label="방 만들기 닫기" @click="close">×</button>
      <p class="eyebrow">새로운 게임</p>
      <h2 id="create-room-title">방 만들기</h2>

      <div class="field">
        <label for="room-title">방 제목</label>
        <input id="room-title" ref="titleInput" v-model="title" maxlength="24" :aria-invalid="Boolean(error)" :aria-describedby="error ? 'create-room-error' : undefined">
      </div>
      <div class="field">
        <label for="room-game">첫 게임</label>
        <select id="room-game" v-model="gameType">
          <option value="LIAR">라이어 게임</option>
          <option value="DRAWING">그림 퀴즈</option>
          <option value="CHOSUNG">초성 퀴즈</option>
          <option value="MAJORITY">다수결 예측</option>
        </select>
      </div>
      <label class="check-row" for="room-public">
        <input id="room-public" v-model="isPublic" type="checkbox">
        <span><strong>공개 로비에 표시</strong><small>꺼 두면 코드를 아는 사람만 입장해요.</small></span>
      </label>
      <label class="check-row" for="room-password-enabled">
        <input id="room-password-enabled" v-model="passwordEnabled" type="checkbox">
        <span><strong>비밀번호 사용</strong><small>방 입장 시 확인해요.</small></span>
      </label>
      <div v-if="passwordEnabled" class="field">
        <label for="room-password">비밀번호</label>
        <input id="room-password" v-model="password" type="password" maxlength="20" autocomplete="new-password" :aria-invalid="Boolean(error)" :aria-describedby="error ? 'create-room-error' : undefined">
      </div>
      <p v-if="error" id="create-room-error" class="field-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button class="secondary" type="button" @click="close">취소</button>
        <button class="primary" type="submit" :disabled="lobby.creating">{{ lobby.creating ? '만드는 중…' : '방 만들기' }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; overflow-y: auto; padding: 1rem; background: rgb(24 22 42 / 55%); }
.dialog-card { position: relative; box-sizing: border-box; width: min(100%, 30rem); border-radius: 1.25rem; background: white; box-shadow: 0 24px 70px rgb(22 16 64 / 30%); padding: 2rem; }
.eyebrow { margin: 0; color: #6652d9; font-size: .75rem; font-weight: 800; letter-spacing: .12em; }
h2 { margin: .35rem 0 1.5rem; }
.close-button { position: absolute; right: 1rem; top: 1rem; border: 0; background: transparent; font-size: 1.6rem; cursor: pointer; }
.field { margin: 1rem 0; }
.field label { display: block; margin-bottom: .4rem; font-size: .88rem; font-weight: 750; }
.field input, .field select { box-sizing: border-box; width: 100%; border: 1px solid #cfd2df; border-radius: .75rem; background: white; font: inherit; padding: .78rem; }
.check-row { display: flex; gap: .75rem; align-items: flex-start; margin: .9rem 0; cursor: pointer; }
.check-row input { margin-top: .3rem; }
.check-row span { display: grid; gap: .15rem; }
.check-row small { color: #747888; }
.field-error { color: #b42318; font-size: .88rem; }
.dialog-actions { display: flex; justify-content: flex-end; gap: .65rem; margin-top: 1.5rem; }
.primary, .secondary { border: 0; border-radius: .75rem; cursor: pointer; font: inherit; font-weight: 800; padding: .8rem 1rem; }
.primary { background: #5b47d6; color: white; }
.secondary { background: #efeff5; color: #343746; }
@media (max-width: 520px) { .dialog-backdrop { align-items: end; padding: 0; } .dialog-card { max-height: 92vh; overflow-y: auto; border-radius: 1.25rem 1.25rem 0 0; padding: 1.5rem; } }
</style>
