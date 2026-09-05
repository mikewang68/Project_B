package com.mt.wms.stockout;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
class StockoutRepository {
    private final JdbcClient jdbc;
    StockoutRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    List<StockoutModels.OrderSummary> orders(long c,long w,long o){return jdbc.sql("""
        SELECT x.id,x.order_code,x.external_order_code,x.outbound_type,x.source,x.state,x.allocation_state,x.pick_state,x.ship_state,
               p.code AS partner_code,p.name AS partner_name,x.planned_date,
               COALESCE(sum(l.planned_qty),0) AS planned_qty,COALESCE(sum(l.allocated_qty),0) AS allocated_qty,
               COALESCE(sum(l.picked_qty),0) AS picked_qty,COALESCE(sum(l.shipped_qty),0) AS shipped_qty,
               x.total_amount,x.created_at,x.completed_at
          FROM stockout_order x LEFT JOIN wms_partner p ON p.id=x.partner_id LEFT JOIN stockout_order_line l ON l.order_id=x.id
         WHERE x.company_id=:c AND x.warehouse_id=:w AND x.owner_id=:o
         GROUP BY x.id,x.order_code,x.external_order_code,x.outbound_type,x.source,x.state,x.allocation_state,x.pick_state,x.ship_state,
                  p.code,p.name,x.planned_date,x.total_amount,x.created_at,x.completed_at
         ORDER BY x.id DESC LIMIT 200
        """).param("c",c).param("w",w).param("o",o).query(StockoutModels.OrderSummary.class).list();}

    OrderHeader order(long id,long c,long w,long o){return jdbc.sql("""
        SELECT x.id,x.order_code,x.external_order_code,x.related_order_code,x.outbound_type,x.source,x.state,x.allocation_state,x.pick_state,x.ship_state,
               x.partner_id,x.planned_date,x.receiver_name,x.receiver_phone,x.receiver_address,x.carrier_code,x.tracking_code,x.total_amount,
               tw.code AS target_warehouse_code,to2.code AS target_owner_code,si.order_code AS transfer_in_order_code,x.transfer_in_order_id,
               x.remark,x.created_at,x.completed_at
          FROM stockout_order x LEFT JOIN wms_warehouse tw ON tw.id=x.target_warehouse_id LEFT JOIN wms_owner to2 ON to2.id=x.target_owner_id
          LEFT JOIN stockin_order si ON si.id=x.transfer_in_order_id
         WHERE x.id=:id AND x.company_id=:c AND x.warehouse_id=:w AND x.owner_id=:o
        """).param("id",id).param("c",c).param("w",w).param("o",o).query(OrderHeader.class).optional().orElseThrow(()->new IllegalArgumentException("出库单不存在"));}
    OrderLock lockOrder(long id,long c,long w,long o){return jdbc.sql("SELECT id,order_code,outbound_type,state,allocation_state,pick_state,ship_state,transfer_in_order_id FROM stockout_order WHERE id=:id AND company_id=:c AND warehouse_id=:w AND owner_id=:o FOR UPDATE").param("id",id).param("c",c).param("w",w).param("o",o).query(OrderLock.class).optional().orElseThrow(()->new IllegalArgumentException("出库单不存在"));}
    List<StockoutModels.LineView> lines(long orderId){return jdbc.sql("""
        SELECT l.id,l.line_no,g.code AS good_code,g.name AS good_name,g.barcode,l.planned_qty,l.allocated_qty,l.picked_qty,l.shipped_qty,
               (l.planned_qty-l.allocated_qty) AS unallocated_qty,(l.allocated_qty-l.picked_qty) AS unpicked_qty,(l.picked_qty-l.shipped_qty) AS unshipped_qty,
               l.supplier_code,l.quality_type,l.batch_code,l.unit_price,l.remark
          FROM stockout_order_line l JOIN wms_good g ON g.id=l.good_id WHERE l.order_id=:id ORDER BY l.line_no
        """).param("id",orderId).query(StockoutModels.LineView.class).list();}
    List<LineRow> lineRows(long orderId){return jdbc.sql("SELECT id,line_no,good_id,planned_qty,allocated_qty,picked_qty,shipped_qty,supplier_code,quality_type,batch_code,unit_price FROM stockout_order_line WHERE order_id=:id ORDER BY line_no FOR UPDATE").param("id",orderId).query(LineRow.class).list();}
    LineRow lockLine(long id,long orderId){return jdbc.sql("SELECT id,line_no,good_id,planned_qty,allocated_qty,picked_qty,shipped_qty,supplier_code,quality_type,batch_code,unit_price FROM stockout_order_line WHERE id=:id AND order_id=:order FOR UPDATE").param("id",id).param("order",orderId).query(LineRow.class).optional().orElseThrow(()->new IllegalArgumentException("出库明细不存在"));}
    long lineIdByBarcode(long orderId,String barcode){return jdbc.sql("SELECT l.id FROM stockout_order_line l JOIN wms_good g ON g.id=l.good_id WHERE l.order_id=:order AND (g.barcode=:barcode OR g.code=:barcode) AND l.picked_qty<l.allocated_qty ORDER BY l.line_no LIMIT 1").param("order",orderId).param("barcode",barcode).query(Long.class).optional().orElseThrow(()->new IllegalArgumentException("出库单中没有该条码对应的待拣明细"));}

    List<StockoutModels.AllocationView> allocations(long orderId,long c,long w,long o){return jdbc.sql("""
        SELECT a.id,l.line_no,g.code AS good_code,g.name AS good_name,loc.code AS location_code,b.batch_code,b.quality_type,b.supplier_code,b.lpn,
               a.allocated_qty,a.picked_qty,a.shipped_qty,a.state,a.created_at
          FROM stockout_allocation a JOIN stockout_order_line l ON l.id=a.order_line_id JOIN wms_good g ON g.id=a.good_id
          JOIN inv_balance b ON b.id=a.balance_id JOIN wms_location loc ON loc.id=a.location_id
         WHERE a.order_id=:id AND a.company_id=:c AND a.warehouse_id=:w AND a.owner_id=:o ORDER BY a.id
        """).param("id",orderId).param("c",c).param("w",w).param("o",o).query(StockoutModels.AllocationView.class).list();}
    List<AllocationRow> lockAllocations(long orderId,long lineId){return jdbc.sql("SELECT id,order_line_id,balance_id,good_id,location_id,allocated_qty,picked_qty,shipped_qty,state FROM stockout_allocation WHERE order_id=:order AND order_line_id=:line AND state='ACTIVE' ORDER BY id FOR UPDATE").param("order",orderId).param("line",lineId).query(AllocationRow.class).list();}
    List<AllocationRow> lockOrderAllocations(long orderId){return jdbc.sql("SELECT id,order_line_id,balance_id,good_id,location_id,allocated_qty,picked_qty,shipped_qty,state FROM stockout_allocation WHERE order_id=:order AND state='ACTIVE' ORDER BY id FOR UPDATE").param("order",orderId).query(AllocationRow.class).list();}
    List<StockoutModels.EventView> events(long orderId,long c,long w,long o){return jdbc.sql("""
        SELECT e.id,e.event_type,e.operation_code,COALESCE(l.line_no,0) AS line_no,g.code AS good_code,g.name AS good_name,loc.code AS location_code,
               e.quantity,u.display_name AS operator_name,e.remark,e.created_at
          FROM stockout_event e LEFT JOIN stockout_order_line l ON l.id=e.order_line_id LEFT JOIN wms_good g ON g.id=l.good_id
          LEFT JOIN wms_location loc ON loc.id=e.location_id JOIN auth_user u ON u.id=e.operator_user_id
         WHERE e.order_id=:id AND e.company_id=:c AND e.warehouse_id=:w AND e.owner_id=:o ORDER BY e.id DESC
        """).param("id",orderId).param("c",c).param("w",w).param("o",o).query(StockoutModels.EventView.class).list();}

    List<PackageRow> packageRows(long orderId){return jdbc.sql("SELECT id,package_code,carrier_code,tracking_code,state,shipped_at,remark,created_at FROM stockout_package WHERE order_id=:id ORDER BY id DESC").param("id",orderId).query(PackageRow.class).list();}
    List<StockoutModels.PackageLineView> packageLines(long packageId){return jdbc.sql("SELECT pl.id,pl.order_line_id,l.line_no,g.code AS good_code,g.name AS good_name,pl.quantity FROM stockout_package_line pl JOIN stockout_order_line l ON l.id=pl.order_line_id JOIN wms_good g ON g.id=l.good_id WHERE pl.package_id=:id ORDER BY l.line_no").param("id",packageId).query(StockoutModels.PackageLineView.class).list();}
    PackageLock lockPackage(long packageId,long orderId){return jdbc.sql("SELECT id,state FROM stockout_package WHERE id=:id AND order_id=:order FOR UPDATE").param("id",packageId).param("order",orderId).query(PackageLock.class).optional().orElseThrow(()->new IllegalArgumentException("包裹不存在"));}
    BigDecimal packedQty(long orderId,long lineId){return jdbc.sql("SELECT COALESCE(sum(pl.quantity),0) FROM stockout_package_line pl JOIN stockout_package p ON p.id=pl.package_id WHERE p.order_id=:order AND pl.order_line_id=:line AND p.state<>'CANCELLED'").param("order",orderId).param("line",lineId).query(BigDecimal.class).single();}

    boolean createProcessed(long c,String key){return jdbc.sql("SELECT count(*) FROM stockout_order WHERE company_id=:c AND idempotency_key=:key").param("c",c).param("key",key).query(Integer.class).single()>0;}
    boolean requestProcessed(long c,String key){return jdbc.sql("SELECT count(*) FROM stockout_request WHERE company_id=:c AND idempotency_key=:key").param("c",c).param("key",key).query(Integer.class).single()>0;}
    void request(long c,Long order,String type,String key){jdbc.sql("INSERT INTO stockout_request(company_id,order_id,action_type,idempotency_key) VALUES(:c,:order,:type,:key)").param("c",c).param("order",order).param("type",type).param("key",key).update();}
    boolean externalExists(long c,long w,long o,String external){return jdbc.sql("SELECT count(*) FROM stockout_order WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND external_order_code=:external AND state<>'CANCELLED'").param("c",c).param("w",w).param("o",o).param("external",external).query(Integer.class).single()>0;}
    Long partnerId(long c,String code){return jdbc.sql("SELECT id FROM wms_partner WHERE company_id=:c AND code=:code AND status='ENABLED'").param("c",c).param("code",code).query(Long.class).optional().orElseThrow(()->new IllegalArgumentException("合作伙伴不存在或已停用"));}
    PartnerRow partner(Long id){if(id==null)return new PartnerRow(null,null);return jdbc.sql("SELECT code,name FROM wms_partner WHERE id=:id").param("id",id).query(PartnerRow.class).optional().orElse(new PartnerRow(null,null));}
    GoodRow good(long c,long o,String code){return jdbc.sql("SELECT id,code,name,barcode,price FROM wms_good WHERE company_id=:c AND owner_id=:o AND (code=:code OR barcode=:code) AND status='ENABLED'").param("c",c).param("o",o).param("code",code).query(GoodRow.class).optional().orElseThrow(()->new IllegalArgumentException("货品不存在或已停用"));}
    TargetScope target(long c,String warehouse,String owner){return jdbc.sql("SELECT w.id AS warehouse_id,o.id AS owner_id FROM wms_warehouse w,wms_owner o WHERE w.company_id=:c AND o.company_id=:c AND w.code=:w AND o.code=:o AND w.status='ENABLED' AND o.status='ENABLED'").param("c",c).param("w",warehouse).param("o",owner).query(TargetScope.class).optional().orElseThrow(()->new IllegalArgumentException("调拨目标仓库或货主不存在"));}
    long targetGood(long c,long owner,String code){return jdbc.sql("SELECT id FROM wms_good WHERE company_id=:c AND owner_id=:o AND code=:code AND status='ENABLED'").param("c",c).param("o",owner).param("code",code).query(Long.class).optional().orElseThrow(()->new IllegalArgumentException("调拨目标货主下缺少货品 "+code));}
    Long stageLocation(long c,long w){return jdbc.sql("SELECT id FROM wms_location WHERE company_id=:c AND warehouse_id=:w AND status='ENABLED' ORDER BY CASE WHEN code='STAGE' THEN 0 ELSE 1 END,code LIMIT 1").param("c",c).param("w",w).query(Long.class).optional().orElseThrow(()->new IllegalArgumentException("目标仓库没有可用库位"));}

    long createOrder(long c,long w,long o,String code,String external,String related,String type,String source,Long partner,LocalDate planned,String receiver,String phone,String address,String carrier,String tracking,BigDecimal amount,Long targetW,Long targetO,long user,String remark,String key){return jdbc.sql("""
        INSERT INTO stockout_order(company_id,warehouse_id,owner_id,order_code,external_order_code,related_order_code,outbound_type,source,partner_id,planned_date,
          receiver_name,receiver_phone,receiver_address,carrier_code,tracking_code,total_amount,target_warehouse_id,target_owner_id,created_by,remark,idempotency_key)
        VALUES(:c,:w,:o,:code,:external,:related,:type,:source,:partner,:planned,:receiver,:phone,:address,:carrier,:tracking,:amount,:targetW,:targetO,:user,:remark,:key) RETURNING id
        """).param("c",c).param("w",w).param("o",o).param("code",code).param("external",external).param("related",related).param("type",type).param("source",source).param("partner",partner).param("planned",planned).param("receiver",receiver).param("phone",phone).param("address",address).param("carrier",carrier).param("tracking",tracking).param("amount",amount).param("targetW",targetW).param("targetO",targetO).param("user",user).param("remark",remark).param("key",key).query(Long.class).single();}
    long createLine(long order,int no,long good,BigDecimal planned,String supplier,String quality,String batch,BigDecimal price,String remark){return jdbc.sql("INSERT INTO stockout_order_line(order_id,line_no,good_id,planned_qty,supplier_code,quality_type,batch_code,unit_price,remark) VALUES(:order,:no,:good,:planned,:supplier,:quality,:batch,:price,:remark) RETURNING id").param("order",order).param("no",no).param("good",good).param("planned",planned).param("supplier",supplier).param("quality",quality).param("batch",batch).param("price",price).param("remark",remark).query(Long.class).single();}

    List<BalanceRow> lockBalances(long c,long w,long o,LineRow line){return jdbc.sql("""
        SELECT b.id,b.good_id,b.location_id,b.batch_code,b.quality_type,b.supplier_code,b.lpn,b.available_qty,b.allocated_qty,b.frozen_qty
          FROM inv_balance b JOIN wms_location l ON l.id=b.location_id
         WHERE b.company_id=:c AND b.warehouse_id=:w AND b.owner_id=:o AND b.good_id=:good AND b.available_qty>0
           AND b.quality_type=:quality AND (:batch='-' OR b.batch_code=:batch) AND (:supplier='-' OR b.supplier_code=:supplier)
         ORDER BY b.expire_date NULLS LAST,b.product_date NULLS LAST,l.operation_order,l.code,b.id FOR UPDATE
        """).param("c",c).param("w",w).param("o",o).param("good",line.goodId()).param("quality",line.qualityType()).param("batch",line.batchCode()).param("supplier",line.supplierCode()).query(BalanceRow.class).list();}
    BalanceRow lockBalance(long id){return jdbc.sql("SELECT id,good_id,location_id,batch_code,quality_type,supplier_code,lpn,available_qty,allocated_qty,frozen_qty FROM inv_balance WHERE id=:id FOR UPDATE").param("id",id).query(BalanceRow.class).optional().orElseThrow(()->new IllegalArgumentException("库存不存在"));}
    void allocateBalance(long id,BigDecimal q){int n=jdbc.sql("UPDATE inv_balance SET available_qty=available_qty-:q,allocated_qty=allocated_qty+:q,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND available_qty>=:q").param("q",q).param("id",id).update();if(n!=1)throw new IllegalArgumentException("库存可用量不足");}
    void unallocateBalance(long id,BigDecimal q){int n=jdbc.sql("UPDATE inv_balance SET available_qty=available_qty+:q,allocated_qty=allocated_qty-:q,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND allocated_qty>=:q").param("q",q).param("id",id).update();if(n!=1)throw new IllegalArgumentException("库存分配量不足");}
    void shipBalance(long id,BigDecimal q){int n=jdbc.sql("UPDATE inv_balance SET allocated_qty=allocated_qty-:q,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND allocated_qty>=:q").param("q",q).param("id",id).update();if(n!=1)throw new IllegalArgumentException("库存分配量不足");}
    long allocation(long c,long w,long o,long order,long line,BalanceRow b,BigDecimal q){return jdbc.sql("INSERT INTO stockout_allocation(company_id,warehouse_id,owner_id,order_id,order_line_id,balance_id,good_id,location_id,allocated_qty) VALUES(:c,:w,:o,:order,:line,:balance,:good,:location,:q) RETURNING id").param("c",c).param("w",w).param("o",o).param("order",order).param("line",line).param("balance",b.id()).param("good",b.goodId()).param("location",b.locationId()).param("q",q).query(Long.class).single();}
    void addAllocated(long line,BigDecimal q){jdbc.sql("UPDATE stockout_order_line SET allocated_qty=allocated_qty+:q,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("q",q).param("id",line).update();}
    void addPicked(long line,long allocation,BigDecimal q){jdbc.sql("UPDATE stockout_order_line SET picked_qty=picked_qty+:q,updated_at=CURRENT_TIMESTAMP WHERE id=:line").param("q",q).param("line",line).update();jdbc.sql("UPDATE stockout_allocation SET picked_qty=picked_qty+:q,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("q",q).param("id",allocation).update();}
    void addShipped(long line,long allocation,BigDecimal q){jdbc.sql("UPDATE stockout_order_line SET shipped_qty=shipped_qty+:q,updated_at=CURRENT_TIMESTAMP WHERE id=:line").param("q",q).param("line",line).update();jdbc.sql("UPDATE stockout_allocation SET shipped_qty=shipped_qty+:q,state=CASE WHEN shipped_qty+:q>=allocated_qty THEN 'SHIPPED' ELSE state END,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("q",q).param("id",allocation).update();}
    void cancelAllocation(long allocation){jdbc.sql("UPDATE stockout_allocation SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",allocation).update();}
    void resetLine(long line){jdbc.sql("UPDATE stockout_order_line SET allocated_qty=shipped_qty,picked_qty=shipped_qty,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",line).update();}

    long operation(long c,long w,long o,String code,long user,String remark,String key){return jdbc.sql("INSERT INTO inv_operation(company_id,warehouse_id,owner_id,operation_code,operation_type,state,operator_user_id,remark,idempotency_key,completed_at) VALUES(:c,:w,:o,:code,'STOCK_OUT','EXECUTED',:user,:remark,:key,CURRENT_TIMESTAMP) RETURNING id").param("c",c).param("w",w).param("o",o).param("code",code).param("user",user).param("remark",remark).param("key",key).query(Long.class).single();}
    void operationLine(long op,BalanceRow b,BigDecimal system,BigDecimal requested,BigDecimal actual,String remark){jdbc.sql("INSERT INTO inv_operation_line(operation_id,good_id,source_location_id,balance_id,system_qty,requested_qty,actual_qty,difference_qty,batch_code,quality_type,remark) VALUES(:op,:good,:location,:balance,:system,:requested,:actual,:diff,:batch,:quality,:remark)").param("op",op).param("good",b.goodId()).param("location",b.locationId()).param("balance",b.id()).param("system",system).param("requested",requested).param("actual",actual).param("diff",actual.subtract(requested)).param("batch",b.batchCode()).param("quality",b.qualityType()).param("remark",remark).update();}
    void transaction(long c,long w,long o,BalanceRow b,String operation,BigDecimal totalChange,BigDecimal availableChange,String key,long user,String remark){BigDecimal before=b.total(),after=before.add(totalChange),availableAfter=b.availableQty().add(availableChange);jdbc.sql("INSERT INTO inv_transaction(company_id,warehouse_id,owner_id,balance_id,good_id,location_id,transaction_type,operation_code,quantity_before,quantity_change,quantity_after,available_before,available_change,available_after,frozen_before,frozen_change,frozen_after,idempotency_key,operator_user_id,remark) VALUES(:c,:w,:o,:balance,:good,:location,'STOCK_OUT',:operation,:before,:change,:after,:ab,:ac,:aa,:frozen,0,:frozen,:key,:user,:remark)").param("c",c).param("w",w).param("o",o).param("balance",b.id()).param("good",b.goodId()).param("location",b.locationId()).param("operation",operation).param("before",before).param("change",totalChange).param("after",after).param("ab",b.availableQty()).param("ac",availableChange).param("aa",availableAfter).param("frozen",b.frozenQty()).param("key",key).param("user",user).param("remark",remark).update();}
    void event(long c,long w,long o,long order,Long line,Long allocation,String type,String operation,BigDecimal q,Long location,long user,String remark){jdbc.sql("INSERT INTO stockout_event(company_id,warehouse_id,owner_id,order_id,order_line_id,allocation_id,event_type,operation_code,quantity,location_id,operator_user_id,remark) VALUES(:c,:w,:o,:order,:line,:allocation,:type,:operation,:q,:location,:user,:remark)").param("c",c).param("w",w).param("o",o).param("order",order).param("line",line).param("allocation",allocation).param("type",type).param("operation",operation).param("q",q).param("location",location).param("user",user).param("remark",remark).update();}

    Progress progress(long order){return jdbc.sql("SELECT COALESCE(sum(planned_qty),0) AS planned,COALESCE(sum(allocated_qty),0) AS allocated,COALESCE(sum(picked_qty),0) AS picked,COALESCE(sum(shipped_qty),0) AS shipped FROM stockout_order_line WHERE order_id=:id").param("id",order).query(Progress.class).single();}
    void states(long order,String allocation,String pick,String ship,String state,boolean completed){jdbc.sql("UPDATE stockout_order SET allocation_state=:allocation,pick_state=:pick,ship_state=:ship,state=:state,completed_at=CASE WHEN :completed THEN CURRENT_TIMESTAMP ELSE completed_at END,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("allocation",allocation).param("pick",pick).param("ship",ship).param("state",state).param("completed",completed).param("id",order).update();}
    void cancelOrder(long order,String remark){jdbc.sql("UPDATE stockout_order SET state='CANCELLED',remark=COALESCE(:remark,remark),version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("remark",remark).param("id",order).update();}

    SerialRow lockSerial(long c,long w,long o,String code){return jdbc.sql("SELECT id,good_id,balance_id,state FROM inv_serial WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND serial_code=:code FOR UPDATE").param("c",c).param("w",w).param("o",o).param("code",code).query(SerialRow.class).optional().orElseThrow(()->new IllegalArgumentException("标签不存在："+code));}
    void pickSerial(long order,long line,long allocation,long serial){jdbc.sql("UPDATE inv_serial SET state='OUTBOUND',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='ACTIVE'").param("id",serial).update();jdbc.sql("INSERT INTO stockout_serial_pick(order_id,order_line_id,allocation_id,serial_id) VALUES(:order,:line,:allocation,:serial)").param("order",order).param("line",line).param("allocation",allocation).param("serial",serial).update();}
    void restoreSerials(long order){jdbc.sql("UPDATE inv_serial SET state='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id IN (SELECT serial_id FROM stockout_serial_pick WHERE order_id=:order)").param("order",order).update();jdbc.sql("DELETE FROM stockout_serial_pick WHERE order_id=:order").param("order",order).update();}

    long createPackage(long c,long w,long o,long order,String code,String carrier,String tracking,long user,String remark){return jdbc.sql("INSERT INTO stockout_package(company_id,warehouse_id,owner_id,order_id,package_code,carrier_code,tracking_code,created_by,remark) VALUES(:c,:w,:o,:order,:code,:carrier,:tracking,:user,:remark) RETURNING id").param("c",c).param("w",w).param("o",o).param("order",order).param("code",code).param("carrier",carrier).param("tracking",tracking).param("user",user).param("remark",remark).query(Long.class).single();}
    void packageLine(long pkg,long line,BigDecimal q){jdbc.sql("INSERT INTO stockout_package_line(package_id,order_line_id,quantity) VALUES(:pkg,:line,:q)").param("pkg",pkg).param("line",line).param("q",q).update();}
    void shipPackage(long pkg){jdbc.sql("UPDATE stockout_package SET state='SHIPPED',shipped_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",pkg).update();}
    void cancelOpenPackages(long order){jdbc.sql("UPDATE stockout_package SET state='CANCELLED' WHERE order_id=:order AND state='OPEN'").param("order",order).update();}

    long createWave(long c,long w,long o,String code,long user,String key,String remark){return jdbc.sql("INSERT INTO stockout_wave(company_id,warehouse_id,owner_id,wave_code,created_by,idempotency_key,remark) VALUES(:c,:w,:o,:code,:user,:key,:remark) RETURNING id").param("c",c).param("w",w).param("o",o).param("code",code).param("user",user).param("key",key).param("remark",remark).query(Long.class).single();}
    void waveOrder(long wave,long order){jdbc.sql("INSERT INTO stockout_wave_order(wave_id,order_id) VALUES(:wave,:order)").param("wave",wave).param("order",order).update();}
    WaveRow wave(long id,long c,long w,long o){return jdbc.sql("SELECT id,wave_code,state,remark,created_at FROM stockout_wave WHERE id=:id AND company_id=:c AND warehouse_id=:w AND owner_id=:o").param("id",id).param("c",c).param("w",w).param("o",o).query(WaveRow.class).optional().orElseThrow(()->new IllegalArgumentException("波次不存在"));}
    List<WaveRow> waves(long c,long w,long o){return jdbc.sql("SELECT id,wave_code,state,remark,created_at FROM stockout_wave WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o ORDER BY id DESC LIMIT 100").param("c",c).param("w",w).param("o",o).query(WaveRow.class).list();}
    List<Long> waveOrderIds(long wave){return jdbc.sql("SELECT order_id FROM stockout_wave_order WHERE wave_id=:wave ORDER BY order_id").param("wave",wave).query(Long.class).list();}
    List<String> waveOrderCodes(long wave){return jdbc.sql("SELECT o.order_code FROM stockout_wave_order wo JOIN stockout_order o ON o.id=wo.order_id WHERE wo.wave_id=:wave ORDER BY o.order_code").param("wave",wave).query(String.class).list();}
    void waveState(long wave,String state){jdbc.sql("UPDATE stockout_wave SET state=:state,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("state",state).param("id",wave).update();}

    long createInbound(long c,long w,long o,String code,String related,String type,long user,String remark,String key){return jdbc.sql("INSERT INTO stockin_order(company_id,warehouse_id,owner_id,order_code,related_order_code,inbound_type,source,state,total_amount,created_by,remark,idempotency_key) VALUES(:c,:w,:o,:code,:related,:type,'CUSTOM','DRAFT',0,:user,:remark,:key) RETURNING id").param("c",c).param("w",w).param("o",o).param("code",code).param("related",related).param("type",type).param("user",user).param("remark",remark).param("key",key).query(Long.class).single();}
    void createInboundLine(long order,int no,long good,BigDecimal q,Long location,String supplier,String quality,String batch,BigDecimal price,String remark){jdbc.sql("INSERT INTO stockin_order_line(order_id,line_no,good_id,planned_qty,preferred_location_id,supplier_code,quality_type,batch_code,unit_price,remark) VALUES(:order,:no,:good,:q,:location,:supplier,:quality,:batch,:price,:remark)").param("order",order).param("no",no).param("good",good).param("q",q).param("location",location).param("supplier",supplier).param("quality",quality).param("batch",batch).param("price",price).param("remark",remark).update();}
    void transferIn(long outbound,long inbound){jdbc.sql("UPDATE stockout_order SET transfer_in_order_id=:inbound WHERE id=:outbound").param("inbound",inbound).param("outbound",outbound).update();}
    void cancelInbound(Long inbound){if(inbound!=null)jdbc.sql("UPDATE stockin_order SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='DRAFT'").param("id",inbound).update();}

    record OrderHeader(Long id,String orderCode,String externalOrderCode,String relatedOrderCode,String outboundType,String source,String state,String allocationState,String pickState,String shipState,Long partnerId,LocalDate plannedDate,String receiverName,String receiverPhone,String receiverAddress,String carrierCode,String trackingCode,BigDecimal totalAmount,String targetWarehouseCode,String targetOwnerCode,String transferInOrderCode,Long transferInOrderId,String remark,java.time.OffsetDateTime createdAt,java.time.OffsetDateTime completedAt){}
    record OrderLock(Long id,String orderCode,String outboundType,String state,String allocationState,String pickState,String shipState,Long transferInOrderId){}
    record LineRow(Long id,int lineNo,Long goodId,BigDecimal plannedQty,BigDecimal allocatedQty,BigDecimal pickedQty,BigDecimal shippedQty,String supplierCode,String qualityType,String batchCode,BigDecimal unitPrice){}
    record GoodRow(Long id,String code,String name,String barcode,BigDecimal price){}
    record PartnerRow(String code,String name){}
    record TargetScope(Long warehouseId,Long ownerId){}
    record BalanceRow(Long id,Long goodId,Long locationId,String batchCode,String qualityType,String supplierCode,String lpn,BigDecimal availableQty,BigDecimal allocatedQty,BigDecimal frozenQty){BigDecimal total(){return availableQty.add(allocatedQty).add(frozenQty);}}
    record AllocationRow(Long id,Long orderLineId,Long balanceId,Long goodId,Long locationId,BigDecimal allocatedQty,BigDecimal pickedQty,BigDecimal shippedQty,String state){}
    record Progress(BigDecimal planned,BigDecimal allocated,BigDecimal picked,BigDecimal shipped){}
    record SerialRow(Long id,Long goodId,Long balanceId,String state){}
    record PackageRow(Long id,String packageCode,String carrierCode,String trackingCode,String state,java.time.OffsetDateTime shippedAt,String remark,java.time.OffsetDateTime createdAt){}
    record PackageLock(Long id,String state){}
    record WaveRow(Long id,String waveCode,String state,String remark,java.time.OffsetDateTime createdAt){}
}
