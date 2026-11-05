import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { flushPromises,shallowMount,type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PsmsInboundTasks from './PsmsInboundTasks.vue'

const mocks=vi.hoisted(()=>({list:vi.fn(),confirm:vi.fn(),retry:vi.fn(),canWrite:false}))
vi.mock('@/api/psmsInbound',()=>({listPsmsInboundTasks:mocks.list,confirmPsmsInbound:mocks.confirm,retryPsmsInboundDelivery:mocks.retry}))
vi.mock('@/stores/auth',()=>({useAuthStore:()=>({canAction:(_menu:string,action:string)=>action==='VIEW'||mocks.canWrite,user:{id:1,tenant:{companyCode:'default',currentWarehouse:{name:'一号仓',code:'W-1'},currentOwner:{name:'货主一',code:'O-1'}}}})}))
let wrapper:VueWrapper|undefined
const task={taskId:'TASK-1',companyCode:'default',ownerCode:'O-1',status:'WAITING_WAREHOUSE',lines:[],deliveryState:'NONE'}
beforeEach(()=>{vi.clearAllMocks();mocks.canWrite=false;mocks.list.mockResolvedValue([task])})
afterEach(()=>wrapper?.unmount())
const render=()=>shallowMount(PsmsInboundTasks,{global:{plugins:[ElementPlus],renderStubDefaultSlot:true,stubs:{ElTableColumn:{template:'<div />'}}}})
describe('dispatch task inbox',()=>{
  it('uses actual server tasks and shows the currently authorized destination',async()=>{
    wrapper=render();await flushPromises()
    expect(wrapper.text()).toContain('一号仓');expect(wrapper.text()).toContain('货主一')
    expect(wrapper.findComponent({name:'ElTable'}).props('data')).toEqual([task])
  })
  it('denies confirmation for read-only or a different owner',async()=>{
    wrapper=render();await flushPromises()
    const vm=wrapper.vm as unknown as {canConfirm:(row:unknown)=>boolean;confirm:(row:unknown)=>Promise<void>}
    expect(vm.canConfirm(task)).toBe(false);await vm.confirm(task);expect(mocks.confirm).not.toHaveBeenCalled()
    mocks.canWrite=true
    expect(vm.canConfirm({...task,ownerCode:'OTHER'})).toBe(false)
  })
  it('clears old rows when the real query fails',async()=>{
    wrapper=render();await flushPromises()
    mocks.list.mockRejectedValue(new Error('任务接口连接失败'))
    await (wrapper.vm as unknown as {reload:()=>Promise<void>}).reload();await flushPromises()
    expect(wrapper.findComponent({name:'ElTable'}).props('data')).toEqual([])
    expect(wrapper.findComponent({name:'ElAlert'}).props('title')).toBe('任务接口连接失败')
  })
})
