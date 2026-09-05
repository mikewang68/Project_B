package com.mt.wms.integration;

import java.time.OffsetDateTime;

final class IntegrationModels {
    private IntegrationModels(){}
    record TaskView(Long id,String taskCode,String name,String taskType,String resourceType,String state,String originalFileName,
                    Integer processedRows,Integer successRows,Integer failureRows,String errorMessage,OffsetDateTime completedAt,OffsetDateTime createdAt){}
    record TaskRow(Long id,Long companyId,Long warehouseId,Long ownerId,Long createdBy,String taskCode,String taskType,String resourceType,
                   String state,String originalFileName,String storedFileName){}
    record QimenConfigView(Long id,String customerId,String appKey,String callbackUrl,String payloadFormat,Boolean enabled,String secretMasked,OffsetDateTime updatedAt){}
    record QimenConfigRequest(String customerId,String appKey,String appSecret,String callbackUrl,String payloadFormat,Boolean enabled){}
    record QimenLogView(Long id,String direction,String methodName,String requestId,String relatedOrderCode,Boolean success,String errorMessage,OffsetDateTime createdAt){}
}
