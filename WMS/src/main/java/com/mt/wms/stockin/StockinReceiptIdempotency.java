package com.mt.wms.stockin;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Uses the same transaction and scoped order lock as stock receipt. */
@Component
class StockinReceiptIdempotency {
    private final JdbcClient jdbc;
    private final PsmsJson json;
    StockinReceiptIdempotency(JdbcClient jdbc,PsmsJson json){this.jdbc=jdbc;this.json=json;}
    record Previous(long warehouseId,long ownerId,long orderId,String requestKind,String requestHash,String responseBody){}
    Optional<StockinModels.ReceiveResult> replay(long company,long warehouse,long owner,Long order,String key,String kind,Object request){
        var found=jdbc.sql("SELECT warehouse_id,owner_id,order_id,request_kind,request_hash,response_body FROM wms_stockin_request WHERE company_id=:c AND request_key=:k")
                .param("c",company).param("k",key).query(Previous.class).optional();
        if(found.isEmpty())return Optional.empty();var previous=found.get();
        if(previous.warehouseId()!=warehouse||previous.ownerId()!=owner||(order!=null&&previous.orderId()!=order)
                ||!previous.requestKind().equals(kind)||!previous.requestHash().equals(json.hash(request)))
            throw PsmsInboundException.conflict("收货请求号已用于不同操作，请核对原请求和原业务范围");
        return Optional.of(json.read(previous.responseBody(),StockinModels.ReceiveResult.class));
    }
    void save(long company,long warehouse,long owner,long order,String key,String kind,Object request,StockinModels.ReceiveResult response){
        try{jdbc.sql("INSERT INTO wms_stockin_request(company_id,warehouse_id,owner_id,order_id,request_key,request_kind,request_hash,response_body) VALUES(:c,:w,:o,:order,:k,:kind,:hash,:body)")
                .param("c",company).param("w",warehouse).param("o",owner).param("order",order).param("k",key).param("kind",kind).param("hash",json.hash(request)).param("body",json.write(response)).update();
        }catch(org.springframework.dao.DataIntegrityViolationException conflict){throw PsmsInboundException.conflict("收货请求号存在并发冲突，请核对原请求后按原编号重试");}
    }
    void lockOwner(long company,long owner){jdbc.sql("SELECT id FROM wms_owner WHERE company_id=:c AND id=:o FOR UPDATE").param("c",company).param("o",owner).query(Long.class).single();}
}
