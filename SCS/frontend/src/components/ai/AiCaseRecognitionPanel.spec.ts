import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AiCaseRecognitionPanel from './AiCaseRecognitionPanel.vue'
const state=vi.hoisted(()=>({user:{readOnly:true},can:()=>true}))
const infer=vi.hoisted(()=>vi.fn())
vi.mock('@/stores/auth',()=>({useAuthStore:()=>state}))
vi.mock('@/api/aiModels',()=>({aiModelApi:{infer,escalate:vi.fn()}}))
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()})}))
describe('explicit model execution',()=>{
 beforeEach(()=>{infer.mockClear();state.user.readOnly=true})
 const open=()=>mount(AiCaseRecognitionPanel,{global:{directives:{permission:()=>{}},stubs:{'el-icon':true}}})
 it('does not infer when an authorized administrator only opens the page',()=>{
  state.user.readOnly=false;const wrapper=open();expect(infer).not.toHaveBeenCalled();wrapper.unmount()
 })
 it('does not execute for a readonly user when changing a case or pressing execute',async()=>{
  const wrapper=open();await wrapper.findAll('.scenario-chip')[1]!.trigger('click');await wrapper.get('.primary-infer-btn').trigger('click');expect(infer).not.toHaveBeenCalled();wrapper.unmount()
 })
})
