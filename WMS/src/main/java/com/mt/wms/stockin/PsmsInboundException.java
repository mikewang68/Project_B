package com.mt.wms.stockin;

public final class PsmsInboundException extends RuntimeException {
    private final int status;
    private final String code;
    public PsmsInboundException(int status,String code,String message){super(message);this.status=status;this.code=code;}
    public int status(){return status;}
    public String code(){return code;}
    static PsmsInboundException conflict(String message){return new PsmsInboundException(409,"PSMS_CONFLICT",message);}
    static PsmsInboundException invalid(String code,String message){return new PsmsInboundException(422,code,message);}
}
