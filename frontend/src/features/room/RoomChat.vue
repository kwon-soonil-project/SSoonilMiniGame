<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { RoomChatMessage } from './roomStore'

export type RoomInputMode = 'CHAT' | 'ANSWER'
export interface RoomInputSubmission { mode: RoomInputMode; body: string }

const props = withDefaults(defineProps<{
  messages: RoomChatMessage[]
  mode?: RoomInputMode
  disabled?: boolean
}>(), { mode: 'CHAT', disabled: false })
const emit = defineEmits<{ submit: [submission: RoomInputSubmission] }>()
const body = ref('')
const messageList = ref<HTMLElement | null>(null)

watch(() => props.messages.length, async () => {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
})

function submit(): void {
  const normalized = body.value.trim()
  if (!normalized) return
  emit('submit', { mode: props.mode, body: normalized })
  body.value = ''
}
</script>

<template>
  <section class="chat-panel" data-region="chat" aria-labelledby="chat-title">
    <header><div><p class="eyebrow">ROOM CHAT</p><h2 id="chat-title">대화</h2></div><span aria-live="polite">{{ messages.length }}개</span></header>
    <ol ref="messageList" aria-live="polite" aria-relevant="additions">
      <li v-for="message in messages" :key="message.messageId">
        <strong>{{ message.nickname }}</strong><p>{{ message.body }}</p>
      </li>
      <li v-if="messages.length === 0" class="empty">첫 메시지를 남겨보세요.</li>
    </ol>
    <form @submit.prevent="submit">
      <label for="room-chat-input">{{ mode === 'ANSWER' ? '정답 입력' : '메시지 입력' }}</label>
      <div><input id="room-chat-input" v-model="body" maxlength="300" autocomplete="off" :disabled="disabled" :placeholder="mode === 'ANSWER' ? '정답을 입력하세요' : '메시지를 입력하세요'"><button type="submit" :disabled="disabled">{{ mode === 'ANSWER' ? '정답 제출' : '전송' }}</button></div>
    </form>
  </section>
</template>

<style scoped>
.chat-panel { display: grid; grid-template-rows: auto minmax(12rem, 1fr) auto; min-width: 0; min-height: 0; border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }
header { display: flex; align-items: end; justify-content: space-between; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }header span { color: #777a89; font-size: .72rem; }
ol { overflow: auto; margin: .9rem 0; padding: 0; list-style: none; }li { margin-bottom: .75rem; }li strong { color: #5b47d6; font-size: .75rem; }li p { display: table; max-width: 88%; margin: .2rem 0 0; border-radius: .15rem .8rem .8rem .8rem; background: #f3f1ff; padding: .55rem .7rem; font-size: .82rem; line-height: 1.45; overflow-wrap: anywhere; }.empty { display: grid; height: 100%; place-items: center; color: #8a8c99; font-size: .8rem; }
form label { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }form div { display: grid; grid-template-columns: 1fr auto; }input { min-width: 0; border: 1px solid #dcdce6; border-radius: .7rem 0 0 .7rem; padding: .72rem; }button { border: 0; border-radius: 0 .7rem .7rem 0; background: #5b47d6; color: white; cursor: pointer; font-weight: 850; padding: 0 .9rem; }
</style>
