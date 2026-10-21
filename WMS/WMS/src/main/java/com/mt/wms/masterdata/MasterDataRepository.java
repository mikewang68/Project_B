package com.mt.wms.masterdata;

import com.mt.wms.masterdata.MasterDataModels.Item;
import com.mt.wms.masterdata.MasterDataModels.SaveRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
class MasterDataRepository {
    private final JdbcClient jdbc;

    MasterDataRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Item> list(MasterResource resource, long companyId, long warehouseId, long ownerId) {
        return switch (resource) {
            case WAREHOUSES -> jdbc.sql("SELECT id,code,name,NULL AS type,status,NULL AS parent_code,NULL AS secondary_code,NULL AS barcode,NULL AS specification,NULL AS unit,contact,telephone,address,remark,NULL::numeric AS quantity,NULL::numeric AS price FROM wms_warehouse WHERE company_id=:c AND status<>'DELETED' ORDER BY code").param("c", companyId).query(Item.class).list();
            case OWNERS -> jdbc.sql("SELECT id,code,name,'OWNER' AS type,status,NULL AS parent_code,NULL AS secondary_code,NULL AS barcode,NULL AS specification,NULL AS unit,contact,telephone,address,remark,NULL::numeric AS quantity,NULL::numeric AS price FROM wms_owner WHERE company_id=:c AND status<>'DELETED' ORDER BY code").param("c", companyId).query(Item.class).list();
            case AREAS -> jdbc.sql("SELECT id,code,name,area_type AS type,status,NULL AS parent_code,NULL AS secondary_code,NULL AS barcode,NULL AS specification,NULL AS unit,NULL AS contact,NULL AS telephone,NULL AS address,remark,NULL::numeric AS quantity,NULL::numeric AS price FROM wms_area WHERE company_id=:c AND warehouse_id=:w AND status<>'DELETED' ORDER BY code").param("c", companyId).param("w", warehouseId).query(Item.class).list();
            case WORK_AREAS -> jdbc.sql("SELECT id,code,name,CASE WHEN system_defined THEN 'SYSTEM' ELSE 'NORMAL' END AS type,status,NULL AS parent_code,NULL AS secondary_code,NULL AS barcode,NULL AS specification,NULL AS unit,NULL AS contact,NULL AS telephone,NULL AS address,remark,NULL::numeric AS quantity,NULL::numeric AS price FROM wms_work_area WHERE company_id=:c AND warehouse_id=:w AND status<>'DELETED' ORDER BY code").param("c", companyId).param("w", warehouseId).query(Item.class).list();
            case LOCATIONS -> jdbc.sql("SELECT l.id,l.code,l.code AS name,l.priority AS type,l.status,a.code AS parent_code,wa.code AS secondary_code,NULL AS barcode,NULL AS specification,NULL AS unit,NULL AS contact,NULL AS telephone,NULL AS address,l.remark,l.max_weight_kg AS quantity,NULL::numeric AS price FROM wms_location l JOIN wms_area a ON a.id=l.area_id JOIN wms_work_area wa ON wa.id=l.work_area_id WHERE l.company_id=:c AND l.warehouse_id=:w AND l.status<>'DELETED' AND a.status<>'DELETED' AND wa.status<>'DELETED' ORDER BY l.operation_order,l.code").param("c", companyId).param("w", warehouseId).query(Item.class).list();
            case PARTNERS -> jdbc.sql("SELECT id,code,name,partner_type AS type,status,NULL AS parent_code,NULL AS secondary_code,NULL AS barcode,NULL AS specification,NULL AS unit,contact,telephone,address,remark,NULL::numeric AS quantity,NULL::numeric AS price FROM wms_partner WHERE company_id=:c AND status<>'DELETED' ORDER BY code").param("c", companyId).query(Item.class).list();
            case CATEGORIES -> jdbc.sql("SELECT id,code,name,NULL AS type,status,NULL AS parent_code,NULL AS secondary_code,NULL AS barcode,NULL AS specification,NULL AS unit,NULL AS contact,NULL AS telephone,NULL AS address,remark,NULL::numeric AS quantity,NULL::numeric AS price FROM wms_category WHERE company_id=:c AND owner_id=:o AND status<>'DELETED' ORDER BY code").param("c", companyId).param("o", ownerId).query(Item.class).list();
            case GOODS -> jdbc.sql("SELECT g.id,g.code,g.name,g.item_type AS type,g.status,c.code AS parent_code,NULL AS secondary_code,g.barcode,g.specification,g.unit,NULL AS contact,NULL AS telephone,NULL AS address,g.remark,g.min_quantity AS quantity,g.price FROM wms_good g JOIN wms_category c ON c.id=g.category_id WHERE g.company_id=:c AND g.owner_id=:o AND g.status<>'DELETED' AND c.status<>'DELETED' ORDER BY g.code").param("c", companyId).param("o", ownerId).query(Item.class).list();
            case COMPONENTS -> jdbc.sql("SELECT gc.id,p.code,p.name,'COMPONENT' AS type,gc.status,s.code AS parent_code,NULL AS secondary_code,s.barcode,NULL AS specification,NULL AS unit,NULL AS contact,NULL AS telephone,NULL AS address,gc.remark,gc.quantity,NULL::numeric AS price FROM wms_good_component gc JOIN wms_good p ON p.id=gc.parent_good_id JOIN wms_good s ON s.id=gc.component_good_id WHERE gc.company_id=:c AND gc.owner_id=:o AND gc.status<>'DELETED' AND p.status<>'DELETED' AND s.status<>'DELETED' ORDER BY p.code,s.code").param("c",companyId).param("o",ownerId).query(Item.class).list();
        };
    }

    long create(MasterResource resource, long userId, long companyId, long warehouseId, long ownerId, SaveRequest r) {
        return switch (resource) {
            case WAREHOUSES -> createWarehouse(userId, companyId, r);
            case OWNERS -> createOwner(userId, companyId, r);
            case AREAS -> jdbc.sql("INSERT INTO wms_area(company_id,warehouse_id,code,name,area_type,status,remark) VALUES(:c,:w,:code,:name,:type,:status,:remark) RETURNING id").param("c",companyId).param("w",warehouseId).param("code",r.code().trim()).param("name",r.name().trim()).param("type",areaType(r.type())).param("status",status(r.status())).param("remark",text(r.remark())).query(Long.class).single();
            case WORK_AREAS -> jdbc.sql("INSERT INTO wms_work_area(company_id,warehouse_id,code,name,system_defined,status,remark) VALUES(:c,:w,:code,:name,FALSE,:status,:remark) RETURNING id").param("c",companyId).param("w",warehouseId).param("code",r.code().trim()).param("name",r.name().trim()).param("status",status(r.status())).param("remark",text(r.remark())).query(Long.class).single();
            case LOCATIONS -> jdbc.sql("INSERT INTO wms_location(company_id,warehouse_id,area_id,work_area_id,code,priority,max_weight_kg,status,remark) SELECT :c,:w,a.id,wa.id,:code,:type,:qty,:status,:remark FROM wms_area a,wms_work_area wa WHERE a.warehouse_id=:w AND a.code=:area AND wa.warehouse_id=:w AND wa.code=:work AND a.status='ENABLED' AND wa.status='ENABLED' RETURNING id").param("c",companyId).param("w",warehouseId).param("code",r.code().trim()).param("type",priority(r.type())).param("qty",number(r.quantity())).param("status",status(r.status())).param("remark",text(r.remark())).param("area",defaultCode(r.parentCode())).param("work",defaultCode(r.secondaryCode())).query(Long.class).optional().orElseThrow(() -> new IllegalArgumentException("库区或工作区不存在"));
            case PARTNERS -> jdbc.sql("INSERT INTO wms_partner(company_id,code,name,partner_type,contact,telephone,address,status,remark) VALUES(:c,:code,:name,:type,:contact,:tel,:address,:status,:remark) RETURNING id").param("c",companyId).param("code",r.code().trim()).param("name",r.name().trim()).param("type",partnerType(r.type())).param("contact",text(r.contact())).param("tel",text(r.telephone())).param("address",text(r.address())).param("status",status(r.status())).param("remark",text(r.remark())).query(Long.class).single();
            case CATEGORIES -> jdbc.sql("INSERT INTO wms_category(company_id,owner_id,code,name,status,remark) VALUES(:c,:o,:code,:name,:status,:remark) RETURNING id").param("c",companyId).param("o",ownerId).param("code",r.code().trim()).param("name",r.name().trim()).param("status",status(r.status())).param("remark",text(r.remark())).query(Long.class).single();
            case GOODS -> jdbc.sql("INSERT INTO wms_good(company_id,owner_id,category_id,code,name,barcode,specification,unit,item_type,min_quantity,price,status,remark) SELECT :c,:o,ca.id,:code,:name,:barcode,:spec,:unit,:type,:qty,:price,:status,:remark FROM wms_category ca WHERE ca.owner_id=:o AND ca.code=:category AND ca.status='ENABLED' RETURNING id").param("c",companyId).param("o",ownerId).param("code",r.code().trim()).param("name",r.name().trim()).param("barcode",blank(r.barcode(),r.code())).param("spec",text(r.specification())).param("unit",text(r.unit())).param("type",blank(r.type(),"ZC")).param("qty",number(r.quantity())).param("price",number(r.price())).param("status",status(r.status())).param("remark",text(r.remark())).param("category",defaultCode(r.parentCode())).query(Long.class).optional().orElseThrow(() -> new IllegalArgumentException("货类不存在"));
            case COMPONENTS -> jdbc.sql("INSERT INTO wms_good_component(company_id,owner_id,parent_good_id,component_good_id,quantity,status,remark) SELECT :c,:o,p.id,s.id,:qty,:status,:remark FROM wms_good p,wms_good s WHERE p.owner_id=:o AND p.code=:code AND s.owner_id=:o AND s.code=:sub AND p.status='ENABLED' AND s.status='ENABLED' RETURNING id").param("c",companyId).param("o",ownerId).param("code",r.code().trim()).param("sub",defaultCode(r.parentCode())).param("qty",positive(r.quantity())).param("status",status(r.status())).param("remark",text(r.remark())).query(Long.class).optional().orElseThrow(()->new IllegalArgumentException("主货品或组件货品不存在"));
        };
    }

    void update(MasterResource resource, long id, long companyId, long warehouseId, long ownerId, SaveRequest r) {
        JdbcClient.StatementSpec sql = switch (resource) {
            case WAREHOUSES -> jdbc.sql("UPDATE wms_warehouse SET name=:name,contact=:contact,telephone=:tel,address=:address,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND status<>'DELETED'");
            case OWNERS -> jdbc.sql("UPDATE wms_owner SET name=:name,contact=:contact,telephone=:tel,address=:address,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND status<>'DELETED'");
            case AREAS -> jdbc.sql("UPDATE wms_area SET name=:name,area_type=:type,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND warehouse_id=:w AND status<>'DELETED'");
            case WORK_AREAS -> jdbc.sql("UPDATE wms_work_area SET name=:name,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND warehouse_id=:w AND status<>'DELETED'");
            case LOCATIONS -> jdbc.sql("UPDATE wms_location SET area_id=(SELECT id FROM wms_area WHERE warehouse_id=:w AND code=:area AND status='ENABLED'),work_area_id=(SELECT id FROM wms_work_area WHERE warehouse_id=:w AND code=:work AND status='ENABLED'),priority=:type,max_weight_kg=:qty,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND warehouse_id=:w AND status<>'DELETED'");
            case PARTNERS -> jdbc.sql("UPDATE wms_partner SET name=:name,partner_type=:type,contact=:contact,telephone=:tel,address=:address,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND status<>'DELETED'");
            case CATEGORIES -> jdbc.sql("UPDATE wms_category SET name=:name,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND owner_id=:o AND status<>'DELETED'");
            case GOODS -> jdbc.sql("UPDATE wms_good SET name=:name,category_id=(SELECT id FROM wms_category WHERE owner_id=:o AND code=:category AND status='ENABLED'),barcode=:barcode,specification=:spec,unit=:unit,item_type=:type,min_quantity=:qty,price=:price,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND owner_id=:o AND status<>'DELETED'");
            case COMPONENTS -> jdbc.sql("UPDATE wms_good_component SET quantity=:qty,status=:status,remark=:remark,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND owner_id=:o AND status<>'DELETED'");
        };
        sql = sql.param("id",id).param("c",companyId).param("name",r.name().trim()).param("status",status(r.status())).param("remark",text(r.remark()));
        if (resource==MasterResource.WAREHOUSES || resource==MasterResource.OWNERS || resource==MasterResource.PARTNERS) sql=sql.param("contact",text(r.contact())).param("tel",text(r.telephone())).param("address",text(r.address()));
        if (resource==MasterResource.AREAS) sql=sql.param("type",areaType(r.type())).param("w",warehouseId);
        if (resource==MasterResource.WORK_AREAS) sql=sql.param("w",warehouseId);
        if (resource==MasterResource.LOCATIONS) sql=sql.param("w",warehouseId).param("area",defaultCode(r.parentCode())).param("work",defaultCode(r.secondaryCode())).param("type",priority(r.type())).param("qty",number(r.quantity()));
        if (resource==MasterResource.PARTNERS) sql=sql.param("type",partnerType(r.type()));
        if (resource==MasterResource.CATEGORIES) sql=sql.param("o",ownerId);
        if (resource==MasterResource.GOODS) sql=sql.param("o",ownerId).param("category",defaultCode(r.parentCode())).param("barcode",blank(r.barcode(),r.code())).param("spec",text(r.specification())).param("unit",text(r.unit())).param("type",blank(r.type(),"ZC")).param("qty",number(r.quantity())).param("price",number(r.price()));
        if (resource==MasterResource.COMPONENTS) sql=sql.param("o",ownerId).param("qty",positive(r.quantity()));
        if (sql.update()!=1) throw new IllegalArgumentException("资料不存在或不属于当前业务范围");
    }


    void logicalDelete(MasterResource resource,long id,long companyId,long warehouseId,long ownerId,long userId,MasterDeletionGuard guard) {
        String table=table(resource);
        String scope="company_id=:c";
        if (List.of(MasterResource.AREAS,MasterResource.WORK_AREAS,MasterResource.LOCATIONS).contains(resource)) scope+=" AND warehouse_id=:w";
        if (List.of(MasterResource.CATEGORIES,MasterResource.GOODS,MasterResource.COMPONENTS).contains(resource)) scope+=" AND owner_id=:o";
        String code=resource==MasterResource.COMPONENTS?"id::text":"code";
        String system=List.of(MasterResource.WORK_AREAS,MasterResource.LOCATIONS).contains(resource)?"system_defined":"FALSE";
        DeleteRow row=jdbc.sql("SELECT "+code+" AS code,"+system+" AS system_defined FROM "+table+" WHERE id=:id AND "+scope+" AND status<>'DELETED' FOR UPDATE")
            .param("id",id).param("c",companyId).param("w",warehouseId).param("o",ownerId).query(DeleteRow.class).optional()
            .orElseThrow(()->new IllegalArgumentException("资料不存在或已删除"));
        if (row.systemDefined() || "default".equals(row.code()) || (resource==MasterResource.LOCATIONS && "STAGE".equals(row.code())))
            throw new IllegalArgumentException("默认或系统内置资料不能删除");
        if(resource==MasterResource.WAREHOUSES) deleteBuiltins(id,companyId,userId,guard);
        if(resource==MasterResource.OWNERS) {
            for(DeleteChild child:jdbc.sql("SELECT id,code,FALSE AS system_defined FROM wms_category WHERE company_id=:c AND owner_id=:id AND status<>'DELETED'").param("c",companyId).param("id",id).query(DeleteChild.class).list()) {
                if(!"default".equals(child.code())) throw new IllegalArgumentException("请先删除货主下的自定义货类");
                guard.check("wms_category",child.id()); markDeleted("wms_category",child.id(),userId);
            }
        }
        guard.check(table,id);
        int changed=jdbc.sql("UPDATE "+table+" SET status='DELETED',deleted_at=CURRENT_TIMESTAMP,deleted_by=:u,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND "+scope+" AND status<>'DELETED'")
            .param("id",id).param("c",companyId).param("w",warehouseId).param("o",ownerId).param("u",userId).update();
        if(changed!=1) throw new IllegalArgumentException("资料不存在或已删除");
    }
    private String table(MasterResource resource) { return switch(resource) {
        case WAREHOUSES -> "wms_warehouse"; case OWNERS -> "wms_owner"; case AREAS -> "wms_area";
        case WORK_AREAS -> "wms_work_area"; case LOCATIONS -> "wms_location"; case PARTNERS -> "wms_partner";
        case CATEGORIES -> "wms_category"; case GOODS -> "wms_good"; case COMPONENTS -> "wms_good_component";
    }; }
    record DeleteRow(String code,boolean systemDefined) {}

    private void deleteBuiltins(long warehouse,long company,long actor,MasterDeletionGuard guard) {
        for(String childTable:List.of("wms_location","wms_work_area","wms_area")) {
            String system=childTable.equals("wms_area")?"FALSE":"system_defined";
            List<DeleteChild> children=jdbc.sql("SELECT id,code,"+system+" AS system_defined FROM "+childTable+" WHERE company_id=:c AND warehouse_id=:w AND status<>'DELETED'")
                .param("c",company).param("w",warehouse).query(DeleteChild.class).list();
            for(DeleteChild child:children) {
                if(!child.systemDefined() && !"default".equals(child.code())) throw new IllegalArgumentException("请先删除仓库下的自定义库位、工作区和库区");
                guard.check(childTable,child.id()); markDeleted(childTable,child.id(),actor);
            }
        }
    }
    private void markDeleted(String table,long id,long actor) {
        jdbc.sql("UPDATE "+table+" SET status='DELETED',deleted_at=CURRENT_TIMESTAMP,deleted_by=:u,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status<>'DELETED'")
            .param("u",actor).param("id",id).update();
    }
    record DeleteChild(Long id,String code,boolean systemDefined) {}

    private long createWarehouse(long userId,long companyId,SaveRequest r){
        long id=jdbc.sql("INSERT INTO wms_warehouse(company_id,code,name,contact,telephone,address,status,remark) VALUES(:c,:code,:name,:contact,:tel,:address,:status,:remark) RETURNING id").param("c",companyId).param("code",r.code().trim()).param("name",r.name().trim()).param("contact",text(r.contact())).param("tel",text(r.telephone())).param("address",text(r.address())).param("status",status(r.status())).param("remark",text(r.remark())).query(Long.class).single();
        long area=jdbc.sql("INSERT INTO wms_area(company_id,warehouse_id,code,name) VALUES(:c,:w,'default','默认库区') RETURNING id").param("c",companyId).param("w",id).query(Long.class).single();
        long work=jdbc.sql("INSERT INTO wms_work_area(company_id,warehouse_id,code,name,system_defined) VALUES(:c,:w,'default','默认工作区',TRUE) RETURNING id").param("c",companyId).param("w",id).query(Long.class).single();
        jdbc.sql("INSERT INTO wms_location(company_id,warehouse_id,area_id,work_area_id,code,system_defined,remark) VALUES(:c,:w,:a,:wa,'STAGE',TRUE,'暂存库位')").param("c",companyId).param("w",id).param("a",area).param("wa",work).update();
        jdbc.sql("INSERT INTO auth_user_warehouse(user_id,warehouse_id) VALUES(:u,:w)").param("u",userId).param("w",id).update(); return id;
    }
    private long createOwner(long userId,long companyId,SaveRequest r){
        long id=jdbc.sql("INSERT INTO wms_owner(company_id,code,name,contact,telephone,address,status,remark) VALUES(:c,:code,:name,:contact,:tel,:address,:status,:remark) RETURNING id").param("c",companyId).param("code",r.code().trim()).param("name",r.name().trim()).param("contact",text(r.contact())).param("tel",text(r.telephone())).param("address",text(r.address())).param("status",status(r.status())).param("remark",text(r.remark())).query(Long.class).single();
        jdbc.sql("INSERT INTO wms_category(company_id,owner_id,code,name) VALUES(:c,:o,'default','默认货类')").param("c",companyId).param("o",id).update();
        jdbc.sql("INSERT INTO auth_user_owner(user_id,owner_id) VALUES(:u,:o)").param("u",userId).param("o",id).update(); return id;
    }
    private String text(String v){return v==null||v.isBlank()?null:v.trim();} private String blank(String v,String d){return v==null||v.isBlank()?d:v.trim();}
    private String status(String v){return "DISABLED".equals(v)?"DISABLED":"ENABLED";} private String defaultCode(String v){return blank(v,"default");}
    private BigDecimal number(BigDecimal v){return v==null?BigDecimal.ZERO:v;} private String priority(String v){return v!=null&&v.matches("L[1-5]")?v:"L3";}
    private BigDecimal positive(BigDecimal v){if(v==null||v.signum()<=0)throw new IllegalArgumentException("组件数量必须大于0");return v;}
    private String areaType(String v){return v!=null&&List.of("STORAGE","TEMP","PIECE","BAD","RETURN","REPAIR").contains(v)?v:"STORAGE";}
    private String partnerType(String v){return v!=null&&List.of("CLIENT","SUPPLIER","CLIENT_SUPPLIER","EXPRESS").contains(v)?v:"CLIENT";}
}
