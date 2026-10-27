#include "vega.h"
#include "ee600.h"
#include "protocol_core.h"
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* 1015559-EN-250117 pp58-60,130-134,139. Limited read-only RTU profile.
 * Status/unit DWORDs use high word first as an explicit simulator convention;
 * OEM text does not specify their word order. Not a commissioned device capture.
 * Conflicting BADC/BACD, configurable 1302 and unmodelled SV/TV/QV are excluded. */
void vega_defaults(vega_state *s){
    memset(s,0,sizeof(*s));s->pv=12.9;s->unit_code=45;s->empty_m=35;s->full_m=1;
}
int vega_valid(const vega_state *s){
    return isfinite(s->pv)&&s->pv>=0&&s->pv<=1000000&&s->unit_code<=65535&&
        isfinite(s->empty_m)&&isfinite(s->full_m)&&s->full_m>=0&&
        s->empty_m>s->full_m&&s->empty_m<=10000&&
        (s->distance_confirmed==0||s->distance_confirmed==1)&&
        (s->calibrated==0||s->calibrated==1)&&
        (s->invalid==0||s->invalid==1)&&(s->nonfinite==0||s->nonfinite==1);
}
double vega_percent(const vega_state *s){
    if(!vega_valid(s)||!s->calibrated||!s->distance_confirmed||s->invalid||s->nonfinite)return NAN;
    double scale;
    switch(s->unit_code){case 45:scale=1;break;case 44:scale=.3048;break;
        case 47:scale=.0254;break;case 48:scale=.01;break;case 49:scale=.001;break;
        default:return NAN;}
    double distance=s->pv*scale;
    if(distance<s->full_m||distance>s->empty_m)return NAN;
    return 100*(s->empty_m-distance)/(s->empty_m-s->full_m);
}
static uint16_t word32(uint32_t bits,unsigned second){return second?(uint16_t)bits:(uint16_t)(bits>>16);}
static uint16_t reg(const vega_state *s,int holding,unsigned address,int *found){
    *found=1;
    if(holding){if(address==200)return 246;if(address==3000)return 0;*found=0;return 0;}
    /* Unimplemented secondary variables are invalid, never invented zero readings. */
    if(address==100||address==101)return word32(14u|(s->invalid?1u:0u),address==101);
    if(address==2000||address==2001)return word32(14u|(s->invalid?1u:0u),address==2001);
    if(address==104||address==105)return word32(s->unit_code,address==105);
    float pv=(float)s->pv;uint32_t bits;
    if(s->nonfinite)bits=0x7fc00000;else memcpy(&bits,&pv,4);
    if(address==106||address==107)return word32(bits,address==106); /* CDAB */
    if(address==2002||address==2003)return word32(bits,address==2003); /* ABCD */
    *found=0;return 0;
}
static int finish(uint8_t *out,size_t n,size_t cap){
    if(cap<n+2)return -1;
    uint16_t crc=ee600_crc(out,n);out[n]=(uint8_t)crc;out[n+1]=(uint8_t)(crc>>8);return (int)n+2;
}
int vega_rtu(const vega_state *s,const uint8_t *in,size_t n,uint8_t *out,size_t cap){
    if(!vega_valid(s)||n<4||cap<5)return -1;
    if(in[0]!=246||ee600_crc(in,n-2)!=((unsigned)in[n-2]|(unsigned)in[n-1]<<8))return 0;
    out[0]=246;out[1]=in[1];int ex=0;
    if(in[1]!=3&&in[1]!=4)ex=1;else if(n!=8)ex=3;
    unsigned address=0,count=0;
    if(!ex){address=(unsigned)in[2]*256+in[3];count=(unsigned)in[4]*256+in[5];
        if(!count||count>125||address+count>65536||cap<5+count*2)ex=3;}
    if(!ex)for(unsigned i=0;i<count;i++){
        int found=0;uint16_t value=reg(s,in[1]==3,address+i,&found);
        if(!found){ex=2;break;}out[3+i*2]=(uint8_t)(value>>8);out[4+i*2]=(uint8_t)value;
    }
    if(ex){out[1]|=0x80;out[2]=(uint8_t)ex;return finish(out,3,cap);}
    out[2]=(uint8_t)(count*2);return finish(out,3+count*2,cap);
}

typedef struct {uint32_t magic,version,bytes,crc;vega_state value;} vega_disk;
int vega_save(const char *dir,const vega_state *s){
    if(!vega_valid(s))return -1;
    char path[1024],tmp[1024];
    if(snprintf(path,sizeof(path),"%s/vega-state.bin",dir)>=(int)sizeof(path)||
       snprintf(tmp,sizeof(tmp),"%s/vega-state.tmp",dir)>=(int)sizeof(tmp))return -1;
    vega_disk d;memset(&d,0,sizeof(d));d.magic=0x56454741;d.version=1;d.bytes=sizeof(*s);d.value=*s;
    d.crc=wms_crc32(&d.value,sizeof(d.value));
    int fd=open(tmp,O_WRONLY|O_CREAT|O_TRUNC,0600);if(fd<0)return -1;
    const uint8_t *p=(const uint8_t*)&d;size_t left=sizeof(d);
    while(left){ssize_t k=write(fd,p,left);if(k<0&&errno==EINTR)continue;if(k<=0){close(fd);return -1;}p+=k;left-=(size_t)k;}
    int ok=fsync(fd);if(close(fd))ok=-1;if(ok||rename(tmp,path))return -1;
    fd=open(dir,O_RDONLY|O_DIRECTORY);if(fd<0)return -1;ok=fsync(fd);close(fd);return ok;
}
int vega_load(const char *dir,vega_state *s){
    char path[1024];if(snprintf(path,sizeof(path),"%s/vega-state.bin",dir)>=(int)sizeof(path))return -1;
    int fd=open(path,O_RDONLY);if(fd<0)return errno==ENOENT?1:-1;
    vega_disk d;size_t got=0;uint8_t *p=(uint8_t*)&d;
    while(got<sizeof(d)){ssize_t k=read(fd,p+got,sizeof(d)-got);if(k<0&&errno==EINTR)continue;if(k<=0)break;got+=(size_t)k;}
    uint8_t extra;ssize_t tail=read(fd,&extra,1);close(fd);
    if(got!=sizeof(d)||tail!=0||d.magic!=0x56454741||d.version!=1||d.bytes!=sizeof(*s)||
       d.crc!=wms_crc32(&d.value,sizeof(d.value))||!vega_valid(&d.value))return -1;
    *s=d.value;return 0;
}
static int query(vega_state *s,unsigned address,unsigned count,uint8_t fc,uint8_t *out){
    uint8_t in[8]={246,fc,(uint8_t)(address>>8),(uint8_t)address,(uint8_t)(count>>8),(uint8_t)count,0,0};
    finish(in,6,sizeof(in));return vega_rtu(s,in,sizeof(in),out,260);
}
int vega_selftest(void){
    vega_state s;vega_defaults(&s);uint8_t out[260];
    if(query(&s,2000,4,4,out)!=13||out[6]!=14||out[7]!=0x41||out[8]!=0x4e||out[9]!=0x66||out[10]!=0x66)return 1;
    if(query(&s,106,2,4,out)!=9||out[3]!=0x66||out[4]!=0x66||out[5]!=0x41||out[6]!=0x4e)return 2;
    if(query(&s,104,2,4,out)!=9||out[6]!=45)return 3;
    if(!isnan(vega_percent(&s)))return 4;
    s.calibrated=1;s.distance_confirmed=1;if(fabs(vega_percent(&s)-65)>1e-9)return 5;
    s.invalid=1;if(!isnan(vega_percent(&s))||query(&s,2000,2,4,out)!=9||out[6]!=15)return 6;
    s.invalid=0;s.nonfinite=1;if(query(&s,2002,2,4,out)!=9||out[3]!=0x7f||out[4]!=0xc0)return 7;
    s.nonfinite=0;s.unit_code=43;if(!isnan(vega_percent(&s)))return 8;
    if(query(&s,2002,2,3,out)!=5||out[2]!=2||query(&s,200,1,3,out)!=7||out[4]!=246)return 9;
    if(query(&s,3000,1,6,out)!=5||out[2]!=1||query(&s,2202,2,4,out)!=5||out[2]!=2)return 10;
    return 0;
}
