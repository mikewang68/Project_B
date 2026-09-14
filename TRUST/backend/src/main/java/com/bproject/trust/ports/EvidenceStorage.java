package com.bproject.trust.ports;

public interface EvidenceStorage {
  long usedBytes() throws Exception;

  String add(byte[] bytes) throws Exception;

  byte[] cat(String cid) throws Exception;
}
