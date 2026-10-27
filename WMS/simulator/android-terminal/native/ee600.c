#include "ee600.h"
#include "protocol_core.h"
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* E+E BA_EE600 v1.13 (07-2026), pp20-24,27-28. RS485 bytes tunneled over TCP.
 * Unresolved status addresses 0x259/0x260 intentionally do not exist here.
 * No serial number, firmware identity or safety-system semantics are invented. */
void ee600_defaults(ee600_state *s){
    memset(s,0,sizeof(*s));s->pressure_pa=497.965972900390625;s->density_kgm3=1.204;
}
int ee600_valid(const ee600_state *s){
    return isfinite(s->pressure_pa)&&s->pressure_pa>=0&&s->pressure_pa<=20000&&
           isfinite(s->k_factor)&&s->k_factor>=0&&s->k_factor<=1000000&&
           isfinite(s->density_kgm3)&&s->density_kgm3>=0.01&&s->density_kgm3<=100&&
           (s->flow_configured==0||s->flow_configured==1)&&
           (!s->flow_configured||s->k_factor>0)&&
           (s->critical_error==0||s->critical_error==1);
}
static double pressure(const ee600_state *s){return fmin(s->pressure_pa,10000);}
double ee600_flow(const ee600_state *s){
    if(s->critical_error||!s->flow_configured)return NAN;
    return s->k_factor*sqrt(pressure(s))*sqrt(1.204/s->density_kgm3);
}
uint16_t ee600_crc(const uint8_t *data,size_t n){
    uint16_t crc=0xffff;
    for(size_t i=0;i<n;i++){crc^=data[i];for(int b=0;b<8;b++)crc=(uint16_t)((crc>>1)^((crc&1)?0xa001:0));}
    return crc;
}
static int finish(uint8_t *out,size_t n,size_t cap){
    if(cap<n+2)return -1;
    uint16_t crc=ee600_crc(out,n);out[n]=(uint8_t)crc;out[n+1]=(uint8_t)(crc>>8);return (int)n+2;
}
static uint16_t float_word(double value,int second){
    float f=(float)value;uint32_t bits;
    if(isnan(f))bits=0x7fc00000;else memcpy(&bits,&f,4);
    /* CDAB: low 16-bit word first; bytes within a word remain big endian. */
    return second?(uint16_t)(bits>>16):(uint16_t)bits;
}
static uint16_t reg(const ee600_state *s,unsigned address,int *found){
    *found=1;double p=s->critical_error?NAN:pressure(s);
    if(address==0x4be||address==0x4bf)return float_word(p,address==0x4bf);
    if(address==0x4c0||address==0x4c1)return float_word(p/1000,address==0x4c1);
    /* Missing fan calibration: this limited profile deliberately exposes no flow register. */
    if((address==0x41e||address==0x41f)&&s->flow_configured)
        return float_word(ee600_flow(s),address==0x41f);
    if(address==0x100b||address==0x100c)
        return s->critical_error?0x8000:(uint16_t)lround(pressure(s));
    *found=0;return 0;
}
int ee600_rtu(const ee600_state *s,const uint8_t *in,size_t n,uint8_t *out,size_t cap){
    if(!ee600_valid(s)||n<4||cap<5)return -1;
    if(in[0]!=43||ee600_crc(in,n-2)!=((unsigned)in[n-2]|(unsigned)in[n-1]<<8))return 0;
    out[0]=43;out[1]=in[1];int ex=0;
    if(in[1]!=3&&in[1]!=4)ex=1;
    else if(n!=8)ex=3;
    unsigned address=0,count=0;
    if(!ex){
        address=(unsigned)in[2]*256+in[3];count=(unsigned)in[4]*256+in[5];
        if(!count||count>125||address+count>65536||cap<5+count*2)ex=3;
    }
    if(!ex)for(unsigned i=0;i<count;i++){
        int found=0;uint16_t value=reg(s,address+i,&found);
        if(!found){ex=2;break;}out[3+i*2]=(uint8_t)(value>>8);out[4+i*2]=(uint8_t)value;
    }
    if(ex){out[1]|=0x80;out[2]=(uint8_t)ex;return finish(out,3,cap);}
    out[2]=(uint8_t)(count*2);return finish(out,3+count*2,cap);
}

typedef struct {uint32_t magic,version,bytes,crc;ee600_state value;} ee_disk;
int ee600_save(const char *dir,const ee600_state *s){
    if(!ee600_valid(s))return -1;
    char path[1024],tmp[1024];
    if(snprintf(path,sizeof(path),"%s/ee600-state.bin",dir)>=(int)sizeof(path)||
       snprintf(tmp,sizeof(tmp),"%s/ee600-state.tmp",dir)>=(int)sizeof(tmp))return -1;
    ee_disk d;memset(&d,0,sizeof(d));d.magic=0x45453630;d.version=1;d.bytes=sizeof(*s);d.value=*s;
    d.crc=wms_crc32(&d.value,sizeof(d.value));
    int fd=open(tmp,O_WRONLY|O_CREAT|O_TRUNC,0600);if(fd<0)return -1;
    const uint8_t *p=(const uint8_t*)&d;size_t left=sizeof(d);
    while(left){ssize_t k=write(fd,p,left);if(k<0&&errno==EINTR)continue;if(k<=0){close(fd);return -1;}p+=k;left-=(size_t)k;}
    int ok=fsync(fd);if(close(fd))ok=-1;if(ok||rename(tmp,path))return -1;
    fd=open(dir,O_RDONLY|O_DIRECTORY);if(fd<0)return -1;ok=fsync(fd);close(fd);return ok;
}
int ee600_load(const char *dir,ee600_state *s){
    char path[1024];if(snprintf(path,sizeof(path),"%s/ee600-state.bin",dir)>=(int)sizeof(path))return -1;
    int fd=open(path,O_RDONLY);if(fd<0)return errno==ENOENT?1:-1;
    ee_disk d;size_t got=0;uint8_t *p=(uint8_t*)&d;
    while(got<sizeof(d)){ssize_t k=read(fd,p+got,sizeof(d)-got);if(k<0&&errno==EINTR)continue;if(k<=0)break;got+=(size_t)k;}
    uint8_t extra;ssize_t tail=read(fd,&extra,1);close(fd);
    if(got!=sizeof(d)||tail!=0||d.magic!=0x45453630||d.version!=1||d.bytes!=sizeof(*s)||
       d.crc!=wms_crc32(&d.value,sizeof(d.value))||!ee600_valid(&d.value))return -1;
    *s=d.value;return 0;
}
static int query(ee600_state *s,unsigned address,unsigned count,uint8_t fc,uint8_t *out){
    uint8_t in[8]={43,fc,(uint8_t)(address>>8),(uint8_t)address,(uint8_t)(count>>8),(uint8_t)count,0,0};
    finish(in,6,sizeof(in));return ee600_rtu(s,in,sizeof(in),out,260);
}
int ee600_selftest(void){
    ee600_state s;ee600_defaults(&s);uint8_t out[260];
    const uint8_t golden[]={0x2b,4,4,0xfb,0xa5,0x43,0xf8,0x61,0xf3};
    if(query(&s,0x4be,2,4,out)!=9||memcmp(out,golden,9))return 1;
    if(query(&s,0x41e,2,4,out)!=5||out[2]!=2)return 2;
    s.critical_error=1;
    if(query(&s,0x4be,2,3,out)!=9||out[3]!=0||out[4]!=0||out[5]!=0x7f||out[6]!=0xc0)return 3;
    if(query(&s,0x100c,1,4,out)!=7||out[3]!=0x80||out[4])return 4;
    s.critical_error=0;s.pressure_pa=20000;
    if(query(&s,0x100c,1,4,out)!=7||out[3]!=0x27||out[4]!=0x10)return 5;
    s.pressure_pa=400;s.k_factor=18;s.flow_configured=1;
    if(fabs(ee600_flow(&s)-360)>1e-9||query(&s,0x41e,2,4,out)!=9)return 6;
    if(query(&s,0x259,1,4,out)!=5||out[2]!=2||query(&s,0x260,1,4,out)!=5||out[2]!=2)return 7;
    if(query(&s,0x4be,0,4,out)!=5||out[2]!=3||query(&s,0x4be,1,6,out)!=5||out[2]!=1)return 8;
    uint8_t bad[]={43,4,4,0xbe,0,2,0,0};
    if(ee600_rtu(&s,bad,8,out,sizeof(out))!=0)return 9;
    return 0;
}
