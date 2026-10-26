package com.mt.wms.finance;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.masterdata.BusinessSequenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

@Service
class FinanceService {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private final FinanceRepository repository; private final TenantContextService tenants; private final BusinessSequenceService sequences;
    FinanceService(FinanceRepository repository,TenantContextService tenants,BusinessSequenceService sequences){this.repository=repository;this.tenants=tenants;this.sequences=sequences;}

    List<FinanceModels.MoneyView> list(String direction,String state,String q,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.money(s.c,s.w,s.o,filterUpper(direction),filterUpper(state),filterText(q));}
    FinanceModels.MoneyDetail detail(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return new FinanceModels.MoneyDetail(repository.one(id,s.c,s.w,s.o),repository.transactions(id,s.c,s.w,s.o));}
    @Transactional FinanceModels.MoneyDetail create(FinanceModels.CreateMoneyRequest r,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);String direction=direction(r.direction());String type=required(r.feeType(),"费用类型不能为空").toUpperCase(Locale.ROOT);FinanceRepository.Partner partner=partner(s.c,r.partnerCode());FinanceModels.CreateMoneyRequest safe=new FinanceModels.CreateMoneyRequest(direction,type,r.relatedOrderCode(),r.partnerCode(),r.payType(),r.amount(),r.accountingDate(),r.remark(),r.idempotencyKey());long id=repository.create(s.c,s.w,s.o,sequences.next("F",p,session),safe,partner,p.userId());return detail(id,p,session);}
    @Transactional FinanceModels.MoneyDetail update(long id,FinanceModels.UpdateMoneyRequest r,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);FinanceRepository.MoneyLock old=repository.lock(id,s.c,s.w,s.o);if(!old.state().equals("DRAFT"))throw new IllegalArgumentException("只有草稿费用单可以修改");if(r.amount().compareTo(old.paidAmount())<0)throw new IllegalArgumentException("费用总额不能小于已收付金额");repository.update(id,r,partner(s.c,r.partnerCode()));return detail(id,p,session);}
    @Transactional FinanceModels.MoneyDetail pay(long id,FinanceModels.PaymentRequest r,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);if(repository.transactionExists(s.c,r.idempotencyKey()))throw new IllegalArgumentException("该收付款请求已处理，请勿重复提交");FinanceRepository.MoneyLock money=repository.lock(id,s.c,s.w,s.o);if(money.state().equals("CANCELLED")||money.state().equals("COMPLETED")||money.state().equals("PART_COMPLETED"))throw new IllegalArgumentException("当前费用单状态不能继续收付款");BigDecimal paid=money.paidAmount().add(r.amount());if(paid.compareTo(money.amount())>0)throw new IllegalArgumentException("收付款金额不能超过未结金额");BigDecimal bad=ZERO;String state;if(paid.compareTo(money.amount())==0)state="COMPLETED";else if(r.partComplete()){state="PART_COMPLETED";bad=money.amount().subtract(paid);}else state="PROCESSING";FinanceRepository.Account account=r.accountId()==null?null:repository.account(r.accountId(),s.c,s.w,s.o);repository.transaction(s.c,s.w,s.o,id,sequences.next("FT",p,session),r.amount(),account,r.referenceUser(),p.userId(),r.idempotencyKey(),r.remark());repository.pay(id,paid,bad,state);return detail(id,p,session);}
    @Transactional FinanceModels.MoneyDetail cancel(long id,FinanceModels.ActionRequest r,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);FinanceRepository.MoneyLock money=repository.lock(id,s.c,s.w,s.o);if(money.paidAmount().signum()>0)throw new IllegalArgumentException("已有收付款流水的费用单不能取消");if(!money.state().equals("CANCELLED"))repository.cancel(id,r.remark());return detail(id,p,session);}

    List<FinanceModels.AccountView> accounts(WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.accounts(s.c,s.w,s.o);}
    @Transactional FinanceModels.AccountView saveAccount(Long id,FinanceModels.SaveAccountRequest r,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);long saved=repository.saveAccount(id,s.c,s.w,s.o,r);return repository.accounts(s.c,s.w,s.o).stream().filter(x->x.id()==saved).findFirst().orElseThrow();}
    FinanceModels.SummaryView summary(String month,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);YearMonth ym=parseMonth(month);List<FinanceModels.FeeSummary> fees=repository.fees(s.c,s.w,s.o,ym.atDay(1),ym.plusMonths(1).atDay(1));BigDecimal ia=sum(fees,"INCOME",FinanceModels.FeeSummary::amount),ip=sum(fees,"INCOME",FinanceModels.FeeSummary::paidAmount),ib=sum(fees,"INCOME",FinanceModels.FeeSummary::badDebtAmount);BigDecimal oa=sum(fees,"OUTCOME",FinanceModels.FeeSummary::amount),op=sum(fees,"OUTCOME",FinanceModels.FeeSummary::paidAmount),ob=sum(fees,"OUTCOME",FinanceModels.FeeSummary::badDebtAmount);return new FinanceModels.SummaryView(ym.toString(),ia,ip,ia.subtract(ip).subtract(ib).max(ZERO),oa,op,oa.subtract(op).subtract(ob).max(ZERO),ip.subtract(op),fees);}
    List<FinanceModels.TrendPoint> trend(WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.trend(s.c,s.w,s.o,YearMonth.now().minusMonths(11).atDay(1));}
    List<FinanceModels.PartnerSummary> partnerSummary(String direction,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.partners(s.c,s.w,s.o,direction(direction));}
    List<FinanceModels.GoodsReport> stockin(LocalDate from,LocalDate to,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.stockin(s.c,s.w,s.o,from,to);}
    List<FinanceModels.GoodsReport> stockout(LocalDate from,LocalDate to,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.stockout(s.c,s.w,s.o,from,to);}

    private Scope scope(WmsPrincipal p,HttpSession session){AuthModels.TenantView t=tenants.current(p,session);return new Scope(p.companyId(),t.currentWarehouse().id(),t.currentOwner().id());}
    private FinanceRepository.Partner partner(long c,String code){return code==null||code.isBlank()?null:repository.partner(c,code.trim());}
    private static String direction(String s){String v=upper(s);if(!v.equals("INCOME")&&!v.equals("OUTCOME"))throw new IllegalArgumentException("收支方向必须是 INCOME 或 OUTCOME");return v;}
    private static String upper(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT);}
    private static String text(String s){return s==null?"":s.trim();}
    private static String filterUpper(String s){return s==null||s.isBlank()?null:s.trim().toUpperCase(Locale.ROOT);}
    private static String filterText(String s){return s==null||s.isBlank()?null:s.trim();}
    private static String required(String s,String msg){if(s==null||s.isBlank())throw new IllegalArgumentException(msg);return s.trim();}
    private static YearMonth parseMonth(String v){try{return v==null||v.isBlank()?YearMonth.now():YearMonth.parse(v);}catch(Exception e){throw new IllegalArgumentException("月份格式必须是 yyyy-MM");}}
    private static BigDecimal sum(List<FinanceModels.FeeSummary> rows,String d,java.util.function.Function<FinanceModels.FeeSummary,BigDecimal> f){return rows.stream().filter(x->x.direction().equals(d)).map(f).reduce(ZERO,BigDecimal::add);}
    private record Scope(long c,long w,long o){}
}
