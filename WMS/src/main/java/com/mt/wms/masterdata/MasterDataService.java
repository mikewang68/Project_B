package com.mt.wms.masterdata;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.auth.sso.WmsCentralScope;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class MasterDataService {
    private final MasterDataRepository repository;
    private final TenantContextService tenantService;
    private final MasterDeletionGuard deletionGuard;
    MasterDataService(MasterDataRepository repository,TenantContextService tenantService,MasterDeletionGuard deletionGuard){this.repository=repository;this.tenantService=tenantService;this.deletionGuard=deletionGuard;}

    List<MasterDataModels.Item> list(MasterResource resource,WmsPrincipal p,HttpSession session){
        AuthModels.TenantView t=tenantService.current(p,session);
        var rows=repository.list(resource,p.companyId(),t.currentWarehouse().id(),t.currentOwner().id());
        if(WmsCentralScope.current()==null)return rows;
        if(resource==MasterResource.WAREHOUSES)
            return rows.stream().filter(row->t.warehouses().stream().anyMatch(allowed->allowed.id().equals(row.id()))).toList();
        if(resource==MasterResource.OWNERS)
            return rows.stream().filter(row->t.owners().stream().anyMatch(allowed->allowed.id().equals(row.id()))).toList();
        return rows;
    }
    private void requireWritable(MasterResource resource,Long id,AuthModels.TenantView t) {
        var scope=WmsCentralScope.current();
        if(scope==null)return;
        if(scope.readOnly())throw new org.springframework.security.access.AccessDeniedException("只读账号不能维护资料");
        boolean companyCatalog=List.of(MasterResource.OWNERS,MasterResource.PARTNERS,MasterResource.CATEGORIES,
                MasterResource.GOODS,MasterResource.COMPONENTS).contains(resource);
        if(companyCatalog&&!scope.adminLevel().equals("L1_SUPER"))
            throw new org.springframework.security.access.AccessDeniedException("公司共享资料由全局管理员维护");
        if(id!=null&&resource==MasterResource.WAREHOUSES
                &&t.warehouses().stream().noneMatch(row->row.id().equals(id)))
            throw new org.springframework.security.access.AccessDeniedException("仓库不在授权范围");
        if(id!=null&&resource==MasterResource.OWNERS&&t.owners().stream().noneMatch(row->row.id().equals(id)))
            throw new org.springframework.security.access.AccessDeniedException("货主不在授权范围");
    }
    @Transactional void delete(MasterResource resource,long id,WmsPrincipal p,HttpSession session){
        AuthModels.TenantView t=tenantService.current(p,session);
        requireWritable(resource,id,t);
        repository.logicalDelete(resource,id,p.companyId(),t.currentWarehouse().id(),t.currentOwner().id(),p.userId(),deletionGuard);
    }
    @Transactional MasterDataModels.Item save(MasterResource resource,Long id,MasterDataModels.SaveRequest body,WmsPrincipal p,HttpSession session){
        AuthModels.TenantView t=tenantService.current(p,session);
        requireWritable(resource,id,t);
        try {
            long savedId=id==null?repository.create(resource,p.userId(),p.companyId(),t.currentWarehouse().id(),t.currentOwner().id(),body):id;
            if(id!=null)repository.update(resource,id,p.companyId(),t.currentWarehouse().id(),t.currentOwner().id(),body);
            return repository.list(resource,p.companyId(),t.currentWarehouse().id(),t.currentOwner().id()).stream().filter(x->x.id().equals(savedId)).findFirst().orElseGet(()->new MasterDataModels.Item(savedId,body.code(),body.name(),body.type(),body.status(),body.parentCode(),body.secondaryCode(),body.barcode(),body.specification(),body.unit(),body.contact(),body.telephone(),body.address(),body.remark(),body.quantity(),body.price()));
        } catch(DataIntegrityViolationException e){throw new IllegalArgumentException("编码、条码或关联资料重复，或存在无效关联");}
    }
}
