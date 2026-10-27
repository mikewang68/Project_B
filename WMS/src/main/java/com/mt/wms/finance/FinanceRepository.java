package com.mt.wms.finance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
class FinanceRepository {
    private final JdbcClient jdbc;
    FinanceRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    List<FinanceModels.MoneyView> money(long c,long w,long o,String direction,String state,String q){return jdbc.sql("""
        SELECT m.id,m.money_code,m.related_order_code,m.direction,m.fee_type,m.partner_code,m.partner_name,m.pay_type,
               m.amount,m.paid_amount,m.bad_debt_amount,GREATEST(m.amount-m.paid_amount-m.bad_debt_amount,0) AS outstanding_amount,
               m.state,m.accounting_date,m.remark,m.created_at,m.finished_at
        FROM finance_money m WHERE m.company_id=:c AND m.warehouse_id=:w AND m.owner_id=:o
          AND (CAST(:direction AS VARCHAR(16)) IS NULL OR m.direction=:direction) AND (CAST(:state AS VARCHAR(16)) IS NULL OR m.state=:state)
          AND (CAST(:q AS VARCHAR(256)) IS NULL OR UPPER(m.money_code) LIKE UPPER('%'||:q||'%') OR UPPER(COALESCE(m.related_order_code,'-')) LIKE UPPER('%'||:q||'%')
               OR UPPER(COALESCE(m.partner_name,'')) LIKE UPPER('%'||:q||'%'))
        ORDER BY m.id DESC LIMIT 300
        """).param("c",c).param("w",w).param("o",o).param("direction",direction).param("state",state).param("q",q).query(FinanceModels.MoneyView.class).list();}

    FinanceModels.MoneyView one(long id,long c,long w,long o){return jdbc.sql("""
        SELECT m.id,m.money_code,m.related_order_code,m.direction,m.fee_type,m.partner_code,m.partner_name,m.pay_type,
               m.amount,m.paid_amount,m.bad_debt_amount,GREATEST(m.amount-m.paid_amount-m.bad_debt_amount,0) AS outstanding_amount,
               m.state,m.accounting_date,m.remark,m.created_at,m.finished_at
        FROM finance_money m WHERE m.id=:id AND m.company_id=:c AND m.warehouse_id=:w AND m.owner_id=:o
        """).param("id",id).param("c",c).param("w",w).param("o",o).query(FinanceModels.MoneyView.class).optional().orElseThrow(()->new IllegalArgumentException("费用单不存在"));}

    MoneyLock lock(long id,long c,long w,long o){return jdbc.sql("SELECT id,amount,paid_amount,bad_debt_amount,state FROM finance_money WHERE id=:id AND company_id=:c AND warehouse_id=:w AND owner_id=:o FOR UPDATE").param("id",id).param("c",c).param("w",w).param("o",o).query(MoneyLock.class).optional().orElseThrow(()->new IllegalArgumentException("费用单不存在"));}
    Partner partner(long c,String code){return jdbc.sql("SELECT id,code,name FROM wms_partner WHERE company_id=:c AND code=:code AND status='ENABLED'").param("c",c).param("code",code).query(Partner.class).optional().orElseThrow(()->new IllegalArgumentException("合作伙伴不存在："+code));}
    long create(long c,long w,long o,String code,FinanceModels.CreateMoneyRequest r,Partner p,long user){return jdbc.sql("""
        INSERT INTO finance_money(company_id,warehouse_id,owner_id,money_code,related_order_code,direction,fee_type,partner_id,partner_code,partner_name,pay_type,amount,accounting_date,created_by,remark,idempotency_key)
        VALUES(:c,:w,:o,:code,:related,:direction,:fee,:partner,:partnerCode,:partnerName,:pay,:amount,:date,:user,:remark,:key) RETURNING id
        """).param("c",c).param("w",w).param("o",o).param("code",code).param("related",blank(r.relatedOrderCode())).param("direction",r.direction()).param("fee",r.feeType()).param("partner",p==null?null:p.id()).param("partnerCode",p==null?null:p.code()).param("partnerName",p==null?null:p.name()).param("pay",blank(r.payType())).param("amount",r.amount()).param("date",r.accountingDate()).param("user",user).param("remark",blank(r.remark())).param("key",r.idempotencyKey()).query(Long.class).single();}
    void update(long id,FinanceModels.UpdateMoneyRequest r,Partner p){jdbc.sql("""
        UPDATE finance_money SET related_order_code=:related,fee_type=:fee,partner_id=:partner,partner_code=:partnerCode,partner_name=:partnerName,
          pay_type=:pay,amount=:amount,accounting_date=:date,remark=:remark,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id
        """).param("id",id).param("related",blank(r.relatedOrderCode())).param("fee",r.feeType()).param("partner",p==null?null:p.id()).param("partnerCode",p==null?null:p.code()).param("partnerName",p==null?null:p.name()).param("pay",blank(r.payType())).param("amount",r.amount()).param("date",r.accountingDate()).param("remark",blank(r.remark())).update();}
    void cancel(long id,String remark){jdbc.sql("UPDATE finance_money SET state='CANCELLED',remark=COALESCE(:remark,remark),version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).param("remark",blank(remark)).update();}
    boolean transactionExists(long c,String key){return jdbc.sql("SELECT count(*) FROM finance_money_transaction WHERE company_id=:c AND idempotency_key=:key").param("c",c).param("key",key).query(Long.class).single()>0;}
    Account account(long id,long c,long w,long o){return jdbc.sql("SELECT id,long_name FROM finance_account WHERE id=:id AND company_id=:c AND warehouse_id=:w AND owner_id=:o AND status='ENABLED'").param("id",id).param("c",c).param("w",w).param("o",o).query(Account.class).optional().orElseThrow(()->new IllegalArgumentException("收付款账户不存在或已停用"));}
    void transaction(long c,long w,long o,long money,String code,BigDecimal amount,Account account,String ref,long user,String key,String remark){jdbc.sql("""
        INSERT INTO finance_money_transaction(company_id,warehouse_id,owner_id,money_id,transaction_code,amount,account_id,account_long_name,reference_user,operator_user_id,idempotency_key,remark)
        VALUES(:c,:w,:o,:money,:code,:amount,:account,:longName,:ref,:user,:key,:remark)
        """).param("c",c).param("w",w).param("o",o).param("money",money).param("code",code).param("amount",amount).param("account",account==null?null:account.id()).param("longName",account==null?null:account.longName()).param("ref",blank(ref)).param("user",user).param("key",key).param("remark",blank(remark)).update();}
    void pay(long id,BigDecimal paid,BigDecimal bad,String state){jdbc.sql("UPDATE finance_money SET paid_amount=:paid,bad_debt_amount=:bad,state=:state,finished_at=CASE WHEN :state IN ('COMPLETED','PART_COMPLETED') THEN CURRENT_TIMESTAMP ELSE NULL END,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).param("paid",paid).param("bad",bad).param("state",state).update();}
    List<FinanceModels.TransactionView> transactions(long id,long c,long w,long o){return jdbc.sql("""
        SELECT t.id,t.transaction_code,t.amount,t.account_long_name,t.reference_user,u.display_name AS operator_name,t.remark,t.created_at
        FROM finance_money_transaction t JOIN auth_user u ON u.id=t.operator_user_id JOIN finance_money m ON m.id=t.money_id
        WHERE t.money_id=:id AND m.company_id=:c AND m.warehouse_id=:w AND m.owner_id=:o ORDER BY t.id DESC
        """).param("id",id).param("c",c).param("w",w).param("o",o).query(FinanceModels.TransactionView.class).list();}

    List<FinanceModels.AccountView> accounts(long c,long w,long o){return jdbc.sql("SELECT id,organ,name,account_no,long_name,status,remark,created_at FROM finance_account WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o ORDER BY id DESC").param("c",c).param("w",w).param("o",o).query(FinanceModels.AccountView.class).list();}
    long saveAccount(Long id,long c,long w,long o,FinanceModels.SaveAccountRequest r){String ln=r.organ().trim()+"/"+r.name().trim()+"/"+r.accountNo().trim();if(id==null)return jdbc.sql("INSERT INTO finance_account(company_id,warehouse_id,owner_id,organ,name,account_no,long_name,status,remark) VALUES(:c,:w,:o,:organ,:name,:no,:long,:status,:remark) RETURNING id").param("c",c).param("w",w).param("o",o).param("organ",r.organ().trim()).param("name",r.name().trim()).param("no",r.accountNo().trim()).param("long",ln).param("status",status(r.status())).param("remark",blank(r.remark())).query(Long.class).single();jdbc.sql("UPDATE finance_account SET organ=:organ,name=:name,account_no=:no,long_name=:long,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND warehouse_id=:w AND owner_id=:o").param("id",id).param("c",c).param("w",w).param("o",o).param("organ",r.organ().trim()).param("name",r.name().trim()).param("no",r.accountNo().trim()).param("long",ln).param("status",status(r.status())).param("remark",blank(r.remark())).update();return id;}

    List<FinanceModels.FeeSummary> fees(long c,long w,long o,LocalDate from,LocalDate to){return jdbc.sql("""
        SELECT direction,fee_type,COALESCE(sum(amount),0) amount,COALESCE(sum(paid_amount),0) paid_amount,COALESCE(sum(bad_debt_amount),0) bad_debt_amount
        FROM finance_money WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND state<>'CANCELLED' AND accounting_date>=:from AND accounting_date<:to GROUP BY direction,fee_type ORDER BY direction,fee_type
        """).param("c",c).param("w",w).param("o",o).param("from",from).param("to",to).query(FinanceModels.FeeSummary.class).list();}
    List<FinanceModels.TrendPoint> trend(long c,long w,long o,LocalDate from){return jdbc.sql("""
        SELECT TO_CHAR(months.mon,'YYYY-MM') AS "month",
          COALESCE(sum(CASE WHEN m.direction='INCOME' THEN m.amount ELSE 0 END),0) income_amount,
          COALESCE(sum(CASE WHEN m.direction='INCOME' THEN m.paid_amount ELSE 0 END),0) income_paid,
          COALESCE(sum(CASE WHEN m.direction='OUTCOME' THEN m.amount ELSE 0 END),0) outcome_amount,
          COALESCE(sum(CASE WHEN m.direction='OUTCOME' THEN m.paid_amount ELSE 0 END),0) outcome_paid
        FROM (SELECT generate_series(DATE_TRUNC('month',CAST(:from AS date)),DATE_TRUNC('month',CURRENT_DATE),'1 month') mon) months
        LEFT JOIN finance_money m ON DATE_TRUNC('month',m.accounting_date)=months.mon AND m.company_id=:c AND m.warehouse_id=:w AND m.owner_id=:o AND m.state<>'CANCELLED'
        GROUP BY months.mon ORDER BY months.mon
        """).param("from",from).param("c",c).param("w",w).param("o",o).query(FinanceModels.TrendPoint.class).list();}
    List<FinanceModels.PartnerSummary> partners(long c,long w,long o,String direction){return jdbc.sql("""
        SELECT COALESCE(partner_code,'-') partner_code,COALESCE(partner_name,'未指定') partner_name,sum(amount) amount,sum(paid_amount) paid_amount,sum(GREATEST(amount-paid_amount-bad_debt_amount,0)) outstanding_amount
        FROM finance_money WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND direction=:direction AND state<>'CANCELLED' GROUP BY partner_code,partner_name ORDER BY amount DESC
        """).param("c",c).param("w",w).param("o",o).param("direction",direction).query(FinanceModels.PartnerSummary.class).list();}
    List<FinanceModels.GoodsReport> stockin(long c,long w,long o,LocalDate from,LocalDate to){return jdbc.sql("""
        SELECT g.code good_code,g.name good_name,g.barcode,g.specification,l.supplier_code,l.quality_type,sum(l.planned_qty) planned_qty,sum(l.received_qty) actual_qty,sum(l.received_qty*l.unit_price) amount
        FROM stockin_order s JOIN stockin_order_line l ON l.order_id=s.id JOIN wms_good g ON g.id=l.good_id
        WHERE s.company_id=:c AND s.warehouse_id=:w AND s.owner_id=:o AND s.state<>'CANCELLED' AND s.created_at>=:from AND s.created_at<CAST(:to AS date)+1
        GROUP BY g.code,g.name,g.barcode,g.specification,l.supplier_code,l.quality_type ORDER BY actual_qty DESC
        """).param("c",c).param("w",w).param("o",o).param("from",from).param("to",to).query(FinanceModels.GoodsReport.class).list();}
    List<FinanceModels.GoodsReport> stockout(long c,long w,long o,LocalDate from,LocalDate to){return jdbc.sql("""
        SELECT g.code good_code,g.name good_name,g.barcode,g.specification,l.supplier_code,l.quality_type,sum(l.planned_qty) planned_qty,sum(l.shipped_qty) actual_qty,sum(l.shipped_qty*l.unit_price) amount
        FROM stockout_order s JOIN stockout_order_line l ON l.order_id=s.id JOIN wms_good g ON g.id=l.good_id
        WHERE s.company_id=:c AND s.warehouse_id=:w AND s.owner_id=:o AND s.state<>'CANCELLED' AND s.created_at>=:from AND s.created_at<CAST(:to AS date)+1
        GROUP BY g.code,g.name,g.barcode,g.specification,l.supplier_code,l.quality_type ORDER BY actual_qty DESC
        """).param("c",c).param("w",w).param("o",o).param("from",from).param("to",to).query(FinanceModels.GoodsReport.class).list();}

    private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
    private static String status(String s){return "DISABLED".equalsIgnoreCase(s)?"DISABLED":"ENABLED";}
    record MoneyLock(Long id,BigDecimal amount,BigDecimal paidAmount,BigDecimal badDebtAmount,String state){}
    record Partner(Long id,String code,String name){}
    record Account(Long id,String longName){}
}
