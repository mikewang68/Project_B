package com.bproject.ehm.asset.ports;
public interface DeviceDocumentStore {
    void put(String id,byte[] bytes);
    byte[] get(String id);
}
