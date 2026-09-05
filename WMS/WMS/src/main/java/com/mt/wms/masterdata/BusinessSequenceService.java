package com.mt.wms.masterdata;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class BusinessSequenceService {
    private final JdbcClient jdbc; private final TenantContextService tenants;
    BusinessSequenceService(JdbcClient jdbc,TenantContextService tenants){this.jdbc=jdbc;this.tenants=tenants;}
    @Transactional public String next(String rawPrefix,WmsPrincipal p,HttpSession session){
        String prefix=rawPrefix==null?"":rawPrefix.trim().toUpperCase();
        if(!prefix.matches("[A-Z0-9]{1,8}"))throw new IllegalArgumentException("单号前缀只能使用1到8位大写字母或数字");
        AuthModels.TenantView t=tenants.current(p,session);String period=LocalDate.now().format(DateTimeFormatter.ofPattern("yyMM"));
        Optional<Row> row=jdbc.sql("SELECT id,current_value FROM wms_business_sequence WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND prefix=:p AND period=:period FOR UPDATE").param("c",p.companyId()).param("w",t.currentWarehouse().id()).param("o",t.currentOwner().id()).param("p",prefix).param("period",period).query(Row.class).optional();
        long value;
        if(row.isPresent()){value=row.get().currentValue()+1;jdbc.sql("UPDATE wms_business_sequence SET current_value=:v,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("v",value).param("id",row.get().id()).update();}
        else{value=1;jdbc.sql("INSERT INTO wms_business_sequence(company_id,warehouse_id,owner_id,prefix,period,current_value) VALUES(:c,:w,:o,:p,:period,1)").param("c",p.companyId()).param("w",t.currentWarehouse().id()).param("o",t.currentOwner().id()).param("p",prefix).param("period",period).update();}
        return prefix+period+"-"+String.format("%03d",value);
    }
    record Row(Long id,long currentValue){}
}
