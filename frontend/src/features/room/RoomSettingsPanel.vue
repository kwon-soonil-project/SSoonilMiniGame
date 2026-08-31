<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { GameType, RoomSettings } from './roomStore'

const props = withDefaults(defineProps<{ settings: RoomSettings; editable: boolean; readOnlyLabel?: string }>(), {
  readOnlyLabel: '방장만 변경',
})
const emit = defineEmits<{ save: [settings: RoomSettings] }>()

function settingsValue(value: RoomSettings): RoomSettings {
  return {
    gameType: value.gameType,
    maxParticipants: value.maxParticipants,
    rounds: value.rounds,
    actionSeconds: value.actionSeconds,
    discussionSeconds: value.discussionSeconds,
    categoryPack: value.categoryPack,
  }
}

const form = reactive<RoomSettings>(settingsValue(props.settings))
watch(() => props.settings, value => Object.assign(form, settingsValue(value)), { deep: true })

const games: Array<{ value: GameType; label: string }> = [
  { value: 'LIAR', label: '라이어 게임' },
]

function save(): void {
  emit('save', settingsValue(form))
}
</script>

<template>
  <section class="settings-panel" data-region="settings" aria-labelledby="settings-title">
    <header>
      <div><p class="eyebrow">GAME SETUP</p><h2 id="settings-title">게임 설정</h2></div>
      <span v-if="!editable">{{ readOnlyLabel }}</span>
    </header>
    <form @submit.prevent="save">
      <label>게임
        <select v-model="form.gameType" :disabled="!editable">
          <option v-for="game in games" :key="game.value" :value="game.value">{{ game.label }}</option>
        </select>
      </label>
      <div class="pair">
        <label>최대 인원<input v-model.number="form.maxParticipants" type="number" min="1" max="12" :disabled="!editable"></label>
        <label>라운드<input v-model.number="form.rounds" type="number" min="1" max="5" :disabled="!editable"></label>
      </div>
      <div class="pair">
        <label>행동 시간(초)<input v-model.number="form.actionSeconds" type="number" min="15" max="45" :disabled="!editable"></label>
        <label>토론 시간(초)<input v-model.number="form.discussionSeconds" type="number" min="60" max="180" :disabled="!editable"></label>
      </div>
      <label>카테고리
        <select v-model="form.categoryPack" :disabled="!editable">
          <option value="all">전체</option>
          <option value="food">음식</option>
          <option value="animal">동물</option>
          <option value="job">직업</option>
          <option value="place">장소</option>
          <option value="household">생활</option>
          <option value="sports">스포츠</option>
          <option value="transport">교통</option>
          <option value="hobby">취미</option>
        </select>
      </label>
      <button v-if="editable" type="submit">설정 저장</button>
    </form>
  </section>
</template>

<style scoped>
.settings-panel { min-width: 0; border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }
header { display: flex; align-items: end; justify-content: space-between; gap: 1rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }header span { color: #777a89; font-size: .73rem; }
form { display: grid; gap: .8rem; margin-top: 1rem; }.pair { display: grid; grid-template-columns: 1fr 1fr; gap: .65rem; }
label { display: grid; gap: .35rem; color: #686b7a; font-size: .75rem; font-weight: 800; }input, select { box-sizing: border-box; width: 100%; min-width: 0; border: 1px solid #dcdce6; border-radius: .65rem; background: white; color: #2f3140; padding: .65rem; }input:disabled, select:disabled { background: #f4f4f8; color: #575a68; opacity: 1; }
button { border: 0; border-radius: .7rem; background: #e9e5ff; color: #513cc6; cursor: pointer; font-weight: 850; padding: .72rem; }
</style>
