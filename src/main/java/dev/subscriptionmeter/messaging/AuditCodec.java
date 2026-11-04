package dev.subscriptionmeter.messaging;

import dev.subscriptionmeter.audit.AuditUnavailableException;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class AuditCodec {
    public static final int PART_BYTES=192*1024, MAX_OPERATION_BYTES=48*1024*1024;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public byte[] encode(AuditEnvelope envelope) { return JSON.writeValueAsBytes(envelope); }
    public AuditEnvelope decode(byte[] data) {
        if(data.length>512*1024) throw new AuditUnavailableException("Audit record exceeds bound");
        AuditEnvelope e=JSON.readValue(data,AuditEnvelope.class);
        if(e.version()!=1 || e.payloadBytes()<1 || e.payloadBytes()>MAX_OPERATION_BYTES || e.parts()<1
                || e.parts()!=(e.payloadBytes()+PART_BYTES-1)/PART_BYTES || e.part()<0 || e.part()>=e.parts()
                || e.operationId()==null || e.operationId().length()>160 || e.requestId()==null || e.requestId().length()>36
                || e.data()==null || e.data().length()>4*((PART_BYTES+2)/3))
            throw new AuditUnavailableException("Invalid audit envelope");
        return e;
    }
    public Iterable<AuditEnvelope> split(AuditOperation op) {
        byte[] bytes=JSON.writeValueAsBytes(op.fields());
        if(bytes.length>MAX_OPERATION_BYTES)throw new AuditUnavailableException("Audit operation exceeds bound");
        int count=(bytes.length+PART_BYTES-1)/PART_BYTES;
        String digest=digest(bytes);
        return ()->new Iterator<>(){
            private int next;
            public boolean hasNext(){return next<count;}
            public AuditEnvelope next(){
                if(!hasNext())throw new NoSuchElementException();
                int i=next++;
                return new AuditEnvelope(1,op.operationId(),op.requestId(),op.kind(),op.observedAt(),i,count,
                    bytes.length,digest,Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,i*PART_BYTES,Math.min(bytes.length,(i+1)*PART_BYTES))));
            }
        };
    }
    @SuppressWarnings("unchecked") public Map<String,Object> fields(byte[] bytes){return JSON.readValue(bytes,Map.class);}
    public static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
