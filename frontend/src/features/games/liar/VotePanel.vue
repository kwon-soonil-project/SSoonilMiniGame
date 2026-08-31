<script setup lang="ts">
import { ref, watch } from 'vue'
import type { RoomParticipant } from '../../room/roomStore'

const props = defineProps<{ participants: RoomParticipant[]; actorId: string; disabled: boolean; revote: boolean; candidateIds?: string[] }>()
const emit = defineEmits<{ submit: [targetId: string] }>()
const targetId = ref('')
watch(() => props.revote, () => { targetId.value = '' })
const candidates = () => {
  const legalRevoteCandidates = new Set(props.candidateIds ?? [])
  return props.participants.filter(participant => participant.actorId !== props.actorId
    && !participant.spectator
    && (!props.revote || legalRevoteCandidates.has(participant.actorId)))
}

function submit(): void {
  if (!targetId.value || props.disabled) return
  emit('submit', targetId.value)
}
</script>

<template>
  <section class="panel" aria-labelledby="vote-title"><p class="eyebrow">{{ revote ? 'REVOTE' : 'VOTING' }}</p><h2 id="vote-title">{{ revote ? '재투표' : '투표' }}</h2><form data-panel="vote" @submit.prevent="submit"><fieldset :disabled="disabled"><legend>라이어로 의심되는 참가자</legend><label v-for="participant in candidates()" :key="participant.actorId"><input v-model="targetId" type="radio" name="liar-vote" :value="participant.actorId"> {{ participant.nickname }}</label></fieldset><button type="submit" aria-label="투표 제출" :disabled="disabled || !targetId">투표 제출</button></form></section>
</template>

<style scoped>
.panel { border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }form { display: grid; gap: .7rem; margin-top: 1rem; }fieldset { display: grid; gap: .5rem; border: 0; margin: 0; padding: 0; }legend { margin-bottom: .45rem; font-size: .78rem; font-weight: 800; }label { border-radius: .65rem; background: #f8f8fc; padding: .6rem; font-size: .85rem; }button { border: 0; border-radius: .7rem; background: #5b47d6; color: white; cursor: pointer; font-weight: 850; padding: .7rem; }button:disabled, fieldset:disabled { cursor: not-allowed; opacity: .55; }
</style>
