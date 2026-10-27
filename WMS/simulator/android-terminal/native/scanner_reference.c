#include "scanner_reference.h"
#include <string.h>
void sr_init(scanner_reference *s){memset(s,0,sizeof(*s));}
void sr_end(scanner_reference *s,unsigned reason){
    if(s->phase==SR_READY||s->phase==SR_WAIT){memset(s->tag,0,sizeof(s->tag));s->deadline_ms=0;s->phase=reason;}
}
int sr_begin(scanner_reference *s,const char *tag){
    size_t n=strlen(tag);if(!n||n>96||s->phase==SR_READY||s->phase==SR_WAIT)return -1;
    for(size_t i=0;i<n;i++)if((unsigned char)tag[i]<32||(unsigned char)tag[i]>126)return -1;
    sr_init(s);memcpy(s->tag,tag,n+1);s->phase=SR_READY;return 0;
}
int sr_frame(const scanner_reference *s,uint8_t *out,size_t cap){
    size_t n=strlen(s->tag);if(s->phase!=SR_READY||!n||cap<n+2)return -1;
    memcpy(out,s->tag,n);out[n]=13;out[n+1]=10;return (int)n+2;
}
int sr_sent(scanner_reference *s,uint64_t now_ms){
    if(s->phase!=SR_READY||s->attempts>=SR_MAX_SENDS||now_ms>UINT64_MAX-SR_TIMEOUT_MS)return -1;
    s->attempts++;s->phase=SR_WAIT;s->deadline_ms=now_ms+SR_TIMEOUT_MS;return 0;
}
void sr_expire(scanner_reference *s,uint64_t now_ms){
    if(s->phase==SR_WAIT&&now_ms>=s->deadline_ms)sr_end(s,SR_TIMEOUT);
}
int sr_reply(scanner_reference *s,uint8_t byte,uint64_t now_ms){
    sr_expire(s,now_ms);if(s->phase!=SR_WAIT)return 0;
    if(byte==6){sr_end(s,SR_ACKED);return 1;}
    if(byte==21){
        if(s->attempts>=SR_MAX_SENDS)sr_end(s,SR_NAK_LIMIT);
        else {s->phase=SR_READY;s->deadline_ms=0;}
        return 1;
    }
    /* Undocumented characters: ignore without extending the absolute deadline. */
    return 0;
}
int sr_selftest(void){
    scanner_reference s;sr_init(&s);uint8_t raw[100];
    if(sr_begin(&s,"SIM-REFERENCE-001")||sr_frame(&s,raw,sizeof(raw))!=19||memcmp(raw,"SIM-REFERENCE-001\r\n",19))return 1;
    for(unsigned i=0;i<SR_MAX_SENDS;i++){
        if(sr_sent(&s,1000+i*10)||s.attempts!=i+1)return 2;
        if(!sr_reply(&s,21,1001+i*10))return 3;
    }
    if(s.phase!=SR_NAK_LIMIT||s.tag[0]||s.attempts!=3)return 4;
    if(sr_begin(&s,"SIM-TIMEOUT")||sr_sent(&s,4000))return 5;
    sr_reply(&s,0,5999);if(s.phase!=SR_WAIT||s.deadline_ms!=6000)return 6;
    sr_reply(&s,6,6000);if(s.phase!=SR_TIMEOUT||s.attempts!=1||s.tag[0])return 7;
    if(sr_begin(&s,"SIM-ACK")||sr_sent(&s,7000)||!sr_reply(&s,6,7001)||s.phase!=SR_ACKED)return 8;
    if(sr_begin(&s,"SIM-DISCONNECT"))return 9;sr_end(&s,SR_DISCONNECTED);
    if(s.phase!=SR_DISCONNECTED||s.tag[0])return 10;
    if(!sr_begin(&s,"bad\nlabel")||!sr_begin(&s,"")||!sr_sent(&s,8000))return 11;
    return 0;
}
