package com.mt.wms.masterdata;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class MasterDataService {
    private final MasterDataRepository repository;
    private final TenantContextService tenantService;
    MasterDataService(MasterDataRepository repository,TenantContextService tenantService){this.repository=repository;this.tenantService=tenantService;}

    List<MasterDataModels.Item> list(MasterResource resource,WmsPrincipal p,HttpSession session){AuthModels.TenantView t=tenantService.current(p,session);return repository.list(resource,p.companyId(),t.currentWarehouse().id(),t.currentOwner().id());}
    @Transactional MasterDataModels.Item save(MasterResource resource,Long id,MasterDataModels.SaveRequest body,WmsPrincipal p,HttpSession session){
        AuthModels.TenantView t=tenantService.current(p,session);
        try {
            long savedId=id==null?repository.create(resource,p.userId(),p.companyId(),t.currentWarehouse().id(),t.currentOwner().id(),body):id;
            if(id!=null)repository.update(resource,id,p.companyId(),t.currentWarehouse().id(),t.currentOwner().id(),body);
            return repository.list(resource,p.companyId(),t.currentWarehouse().id(),t.currentOwner().id()).stream().filter(x->x.id().equals(savedId)).findFirst().orElseGet(()->new MasterDataModels.Item(savedId,body.code(),body.name(),body.type(),body.status(),body.parentCode(),body.secondaryCode(),body.barcode(),body.specification(),body.unit(),body.contact(),body.telephone(),body.address(),body.remark(),body.quantity(),body.price()));
        } catch(DataIntegrityViolationException e){throw new IllegalArgumentException("编码、条码或关联资料重复，或存在无效关联");}
    }
}
