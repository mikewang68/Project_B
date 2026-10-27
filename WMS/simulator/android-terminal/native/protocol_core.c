#include "protocol_core.h"
#include "device_defs.h"
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* All addresses in device_defs.h are laboratory PLC engineering addresses.
 * They are not Siemens or plant OEM process-variable addresses. */
void wms_defaults(wms_state *s) {
    memset(s, 0, sizeof(*s)); s->next_seq = 1;
    s->gross_kg = 32500; s->tare_kg = 12500; s->stable = 1;
    s->level_percent = 65; s->pressure_kpa = 5; s->flow_m3h = 360;
}
int wms_scan_add(wms_state *s, const char *tag) {
    size_t n = tag ? strlen(tag) : 0;
    if (!n || n > WMS_TAG_LIMIT || s->count >= WMS_QUEUE_LIMIT || s->next_seq == UINT64_MAX) return -1;
    for (size_t i=0; i<n; ++i) if ((unsigned char)tag[i]<32 || (unsigned char)tag[i]>126) return -1;
    wms_scan *q = &s->queue[s->count++]; memset(q, 0, sizeof(*q));
    q->seq = s->next_seq++; memcpy(q->tag, tag, n); return 0;
}
int wms_scan_ack(wms_state *s, uint64_t seq) {
    if (!s->count || s->queue[0].seq != seq) return -1;
    memmove(s->queue, s->queue+1, (--s->count)*sizeof(wms_scan));
    memset(&s->queue[s->count], 0, sizeof(wms_scan)); return 0;
}
int wms_scan_frame(const wms_scan *scan, uint8_t *out, size_t cap) {
    size_t n=strlen(scan->tag); if (!n || cap < n+2) return -1;
    memcpy(out, scan->tag, n); out[n]=13; out[n+1]=10; return (int)n+2;
}
int wms_scale_frame(const wms_state *s, uint8_t *out, size_t cap) {
    /* IND231/236 Toledo Continuous: STX + A/B/C + six net + six tare + CR + checksum.
     * kg, x1 build, no decimal places; signed net in status B, tare nonnegative. */
    if (cap<18 || !isfinite(s->gross_kg) || !isfinite(s->tare_kg) ||
        s->gross_kg<0 || s->tare_kg<0 || s->gross_kg>999999 || s->tare_kg>999999) return -1;
    long net=lround(s->gross_kg-s->tare_kg), tare=lround(s->tare_kg);
    out[0]=2; out[1]=0x2a; /* decimal code 2, build x1 (bit 3), bit 5 */
    out[2]=0x31 | (net<0 ? 2:0) | (!s->stable ? 8:0) | (s->fault ? 4:0);
    out[3]=0x20; /* kg via B.bit4; C.bit5 constant */
    char text[16]; snprintf(text,sizeof(text),"%6ld%6ld",labs(net),tare);
    memcpy(out+4,text,12); out[16]=13;
    unsigned sum=0; for(int i=0;i<17;i++) sum+=out[i]; out[17]=(uint8_t)(-(int)sum)&0x7f;
    return 18;
}
static double model_value(const wms_state *s, const wms_def *d) {
    switch(d->model) {
        case 1:return s->level_percent; case 2:return s->pressure_kpa;
        case 3:return s->flow_m3h; case 4:return s->occupied;
        case 5:return s->person_present; case 6:return s->blockage;
        case 7:return s->leakage; case 8:return s->fault;
        case 9:return s->level_percent/100.0*2500.0*1200.0/1000.0;
        case 10:return 3000.0-s->level_percent/100.0*2500.0*1200.0/1000.0;
        case 11:return s->level_percent>=90; case 12:return s->level_percent>=80;
        case 13:return s->level_percent<=20; case 14:return s->level_percent<=10;
        default:return d->normal;
    }
}
static uint16_t reg_value(const wms_state *s,int device,unsigned address,int *found) {
    for(unsigned i=0;i<WMS_DEF_COUNT;i++) {
        const wms_def *d=&wms_defs[i];
        if(d->device!=device || address<d->address || address>=d->address+d->words) continue;
        *found=1; double v=model_value(s,d);
        if(d->kind==0) return v!=0;
        if(d->kind==1) return (uint16_t)v;
        if(d->kind==2) {
            float f=(float)v; uint32_t bits; memcpy(&bits,&f,4);
            return address==d->address ? (uint16_t)(bits>>16):(uint16_t)bits;
        }
        const char *text=d->model==4?(s->occupied?"SIM-OCCUPIED":"SIM-IDLE"):
                         d->model==8?(s->fault?"SIM-FAULT":"SIM-NONE"):d->text;
        unsigned off=(address-d->address)*2; size_t len=strlen(text);
        return (uint16_t)((off<len?(unsigned char)text[off]:0)<<8) |
               (off+1<len?(unsigned char)text[off+1]:0);
    }
    return 0;
}
int wms_modbus(const wms_state *s,int device,const uint8_t *in,size_t n,uint8_t *out,size_t cap) {
    if(n<8 || cap<9 || in[2] || in[3] || ((unsigned)in[4]*256+in[5])+6!=n || in[6]!=1) return -1;
    memcpy(out,in,7); int ex=0;
    if(in[7]!=3) ex=1; /* Every write function is rejected. */
    else if(n!=12) ex=3;
    unsigned address=0,count=0;
    if(!ex) {
        address=(unsigned)in[8]*256+in[9]; count=(unsigned)in[10]*256+in[11];
        if(!count || count>125 || cap<9+count*2 || address+count>65536) ex=3;
    }
    if(!ex) for(unsigned i=0;i<count;i++) {
        int found=0; uint16_t r=reg_value(s,device,address+i,&found);
        if(!found){ex=2;break;} out[9+i*2]=r>>8;out[10+i*2]=r&255;
    }
    out[4]=0;
    if(ex){out[5]=3;out[7]=in[7]|0x80;out[8]=ex;return 9;}
    out[4]=(uint8_t)((3+count*2)>>8);out[5]=(uint8_t)(3+count*2);
    out[7]=3;out[8]=(uint8_t)(count*2);return 9+count*2;
}
uint32_t wms_crc32(const void *p,size_t n) {
    const uint8_t *b=p;uint32_t c=~0u;
    for(size_t i=0;i<n;i++){c^=b[i];for(int j=0;j<8;j++)c=(c>>1)^((0u-(c&1))&0xedb88320u);}
    return ~c;
}
typedef struct {uint32_t magic,version,bytes,crc;wms_state state;} disk_state;
int wms_save(const char *dir,const wms_state *s) {
    char path[1024],tmp[1024];
    if(snprintf(path,sizeof(path),"%s/protocol-state.bin",dir)>=(int)sizeof(path) ||
       snprintf(tmp,sizeof(tmp),"%s/protocol-state.tmp",dir)>=(int)sizeof(tmp))return -1;
    disk_state data;memset(&data,0,sizeof(data));data.magic=0x574d5331;data.version=1;
    data.bytes=sizeof(wms_state);data.state=*s;data.crc=wms_crc32(&data.state,sizeof(data.state));
    int fd=open(tmp,O_WRONLY|O_CREAT|O_TRUNC,0600);if(fd<0)return -1;
    const uint8_t *p=(const uint8_t*)&data;size_t left=sizeof(data);
    while(left){ssize_t n=write(fd,p,left);if(n<=0){close(fd);return -1;}p+=n;left-=(size_t)n;}
    int ok=fsync(fd);if(close(fd))ok=-1;if(ok || rename(tmp,path))return -1;
    fd=open(dir,O_RDONLY|O_DIRECTORY);if(fd>=0){ok=fsync(fd);close(fd);}else ok=-1;
    return ok;
}
int wms_load(const char *dir,wms_state *s) {
    char path[1024];if(snprintf(path,sizeof(path),"%s/protocol-state.bin",dir)>=(int)sizeof(path))return -1;
    int fd=open(path,O_RDONLY);if(fd<0)return errno==ENOENT?1:-1;
    disk_state d;uint8_t *p=(uint8_t*)&d;size_t got=0;
    while(got<sizeof(d)){ssize_t n=read(fd,p+got,sizeof(d)-got);if(n<=0)break;got+=(size_t)n;}
    char extra;ssize_t tail=read(fd,&extra,1);close(fd);
    if(got!=sizeof(d)||tail!=0||d.magic!=0x574d5331||d.version!=1||d.bytes!=sizeof(wms_state)||
       d.crc!=wms_crc32(&d.state,sizeof(d.state))||d.state.count>WMS_QUEUE_LIMIT||!d.state.next_seq)return -1;
    for(unsigned i=0;i<d.state.count;i++)if(!memchr(d.state.queue[i].tag,0,WMS_TAG_LIMIT+1)||
        !d.state.queue[i].seq||d.state.queue[i].seq>=d.state.next_seq)return -1;
    *s=d.state;return 0;
}
int wms_selftest(void) {
    wms_state s;uint8_t out[300];wms_defaults(&s);
    if(wms_scan_add(&s,"SIM-STEEL-B001")||wms_scan_frame(&s.queue[0],out,sizeof(out))!=16||
       memcmp(out,"SIM-STEEL-B001\r\n",16))return 1;
    if(wms_scan_ack(&s,9)!=-1||wms_scan_ack(&s,1)||s.count)return 2;
    if(wms_scan_add(&s,"BAD\nTAG")!=-1)return 3;
    if(wms_scale_frame(&s,out,sizeof(out))!=18||memcmp(out+4," 20000 12500",12))return 4;
    unsigned sum=0;for(int i=0;i<18;i++)sum+=out[i];if(sum&0x7f)return 5;
    const uint8_t q[]={0x12,0x34,0,0,0,6,1,3,0,0,0,1};
    if(wms_modbus(&s,1,q,sizeof(q),out,sizeof(out))!=11||out[0]!=0x12||out[7]!=3)return 6;
    uint8_t wr[12];memcpy(wr,q,12);wr[7]=6;
    if(wms_modbus(&s,1,wr,12,out,sizeof(out))!=9||out[7]!=0x86||out[8]!=1)return 7;
    if(wms_crc32("123456789",9)!=0xcbf43926)return 8;
    return 0;
}
