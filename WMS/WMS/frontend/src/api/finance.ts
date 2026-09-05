import { apiFetch } from './http'

export interface Money { id:number;moneyCode:string;relatedOrderCode?:string;direction:'INCOME'|'OUTCOME';feeType:string;partnerCode?:string;partnerName?:string;payType?:string;amount:number;paidAmount:number;badDebtAmount:number;outstandingAmount:number;state:string;accountingDate:string;remark?:string;createdAt:string;finishedAt?:string }
export interface MoneyTransaction { id:number;transactionCode:string;amount:number;accountLongName?:string;referenceUser?:string;operatorName:string;remark?:string;createdAt:string }
export interface MoneyDetail { money:Money;transactions:MoneyTransaction[] }
export interface FinanceAccount { id:number;organ:string;name:string;accountNo:string;longName:string;status:string;remark?:string;createdAt:string }
export interface FeeSummary { direction:string;feeType:string;amount:number;paidAmount:number;badDebtAmount:number }
export interface FinanceSummary { month:string;incomeAmount:number;incomePaid:number;incomeOutstanding:number;outcomeAmount:number;outcomePaid:number;outcomeOutstanding:number;grossProfit:number;fees:FeeSummary[] }
export interface TrendPoint { month:string;incomeAmount:number;incomePaid:number;outcomeAmount:number;outcomePaid:number }
export interface PartnerSummary { partnerCode:string;partnerName:string;amount:number;paidAmount:number;outstandingAmount:number }
export interface GoodsReport { goodCode:string;goodName:string;barcode:string;specification?:string;supplierCode:string;qualityType:string;plannedQty:number;actualQty:number;amount:number }

async function write<T>(path:string,method:'POST'|'PUT',data:unknown):Promise<T>{const csrf=await apiFetch<{headerName:string;token:string}>('/api/v1/auth/csrf');return apiFetch(path,{method,data,headers:{[csrf.headerName]:csrf.token}})}
export const listMoney=(params:Record<string,string>={}):Promise<Money[]>=>apiFetch('/api/v1/finance/money',{params})
export const getMoney=(id:number):Promise<MoneyDetail>=>apiFetch(`/api/v1/finance/money/${id}`)
export const createMoney=(data:unknown):Promise<MoneyDetail>=>write('/api/v1/finance/money','POST',data)
export const updateMoney=(id:number,data:unknown):Promise<MoneyDetail>=>write(`/api/v1/finance/money/${id}`,'PUT',data)
export const payMoney=(id:number,data:unknown):Promise<MoneyDetail>=>write(`/api/v1/finance/money/${id}/transactions`,'POST',data)
export const cancelMoney=(id:number,data:unknown):Promise<MoneyDetail>=>write(`/api/v1/finance/money/${id}/cancel`,'POST',data)
export const listAccounts=():Promise<FinanceAccount[]>=>apiFetch('/api/v1/finance/accounts')
export const saveAccount=(id:number|undefined,data:unknown):Promise<FinanceAccount>=>write(id?`/api/v1/finance/accounts/${id}`:'/api/v1/finance/accounts',id?'PUT':'POST',data)
export const getFinanceSummary=(month:string):Promise<FinanceSummary>=>apiFetch('/api/v1/finance/summary',{params:{month}})
export const getFinanceTrend=():Promise<TrendPoint[]>=>apiFetch('/api/v1/finance/trend')
export const getPartnerSummary=(direction:string):Promise<PartnerSummary[]>=>apiFetch('/api/v1/finance/partner-summary',{params:{direction}})
export const getGoodsReport=(type:'stockin'|'stockout',from:string,to:string):Promise<GoodsReport[]>=>apiFetch(`/api/v1/finance/reports/${type}`,{params:{from,to}})
