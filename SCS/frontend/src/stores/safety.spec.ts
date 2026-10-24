import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSafetyStore } from './safety'
import { demoSnapshot } from '@/mock/snapshot'
import * as http from '@/api/http'
import type { SafetySnapshot } from '@/types/safety'
vi.mock('@/api/live',()=>({SafetyLiveClient:class {connect(){}stop(){}}}))

describe('safety store', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(()=>vi.restoreAllMocks())

  it('derives active risks from the baseline snapshot', () => {
    const store = useSafetyStore()
    store.snapshot=structuredClone(demoSnapshot)
    expect(store.activeAlarms.length).toBe(3)
    expect(store.urgentAlarms.length).toBe(1)
    expect(store.riskyDevices.length).toBe(2)
    expect(store.pendingAi.length).toBe(2)
  })
  it('clears business records on logout and starts without synthetic risks',()=>{
    const store=useSafetyStore();expect(store.snapshot.people).toHaveLength(0);expect(store.activeAlarms).toHaveLength(0)
    store.snapshot=structuredClone(demoSnapshot);store.dispose()
    expect(store.snapshot.people).toHaveLength(0);expect(store.activeAlarms).toHaveLength(0);expect(store.usingFallback).toBe(true)
  })
  it('does not restore a response that arrives after logout',async()=>{
    let finish!:(value:SafetySnapshot)=>void
    const pending=new Promise<SafetySnapshot>(resolve=>{finish=resolve})
    vi.spyOn(http,'apiRequest').mockReturnValueOnce(pending)
    const store=useSafetyStore();const loading=store.initialize();store.dispose()
    finish(structuredClone(demoSnapshot));await loading
    expect(store.snapshot.people).toHaveLength(0);expect(store.activeAlarms).toHaveLength(0)
  })
})

