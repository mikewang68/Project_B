import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSafetyStore } from './safety'

describe('safety store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('derives active risks from the baseline snapshot', () => {
    const store = useSafetyStore()
    expect(store.activeAlarms.length).toBe(3)
    expect(store.urgentAlarms.length).toBe(1)
    expect(store.riskyDevices.length).toBe(2)
    expect(store.pendingAi.length).toBe(2)
  })
})

