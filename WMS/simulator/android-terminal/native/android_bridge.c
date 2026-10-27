#include "protocol_core.h"
#include "ee600.h"
#include "vega.h"
#include "scanner_reference.h"
#include <jni.h>
#include <math.h>
#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static wms_state state;
static ee600_state ee_state;
static vega_state radar_state;
static scanner_reference sr_state;
static int sr_accepting=0;
static char directory[900];
#define CHANNELS 8
static int initialized=0,running=0,worker_count=0,listeners[CHANNELS]={-1,-1,-1,-1,-1,-1,-1,-1};
static pthread_t workers[CHANNELS];
static const int ports[CHANNELS]={19061,19062,1502,1503,1504,19063,19064,19065};
static unsigned connections[CHANNELS];

static int is_running(void){int r;pthread_mutex_lock(&lock);r=running;pthread_mutex_unlock(&lock);return r;}
static void trace(int channel,const char *direction,const uint8_t *b,size_t n,uint64_t seq){
    char path[1024];snprintf(path,sizeof(path),"%s/raw-frames.log",directory);
    pthread_mutex_lock(&lock);
    struct stat st;if(!stat(path,&st)&&st.st_size>2*1024*1024){char old[1050];snprintf(old,sizeof(old),"%s.previous",path);rename(path,old);}
    FILE *f=fopen(path,"ab");if(f){
        struct timespec t;clock_gettime(CLOCK_REALTIME,&t);
        fprintf(f,"%lld.%03ld port=%d direction=%s phone_seq=%llu hex=",(long long)t.tv_sec,t.tv_nsec/1000000,ports[channel],direction,(unsigned long long)seq);
        for(size_t i=0;i<n;i++)fprintf(f,"%02x",b[i]);fputc('\n',f);fclose(f);
    }pthread_mutex_unlock(&lock);
}
static int send_all(int fd,const uint8_t *b,size_t n){
    while(n){ssize_t k=send(fd,b,n,MSG_NOSIGNAL);if(k<0&&errno==EINTR)continue;if(k<=0)return -1;b+=k;n-=(size_t)k;}return 0;
}
static int read_exact(int fd,uint8_t *b,size_t n){
    while(n&&is_running()){ssize_t k=recv(fd,b,n,0);if(k<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR))continue;
        if(k<=0)return -1;b+=k;n-=(size_t)k;}return n?-1:0;
}
static void scanner(int fd){
    while(is_running()){
        wms_scan q;pthread_mutex_lock(&lock);int count=(int)state.count;if(count)q=state.queue[0];pthread_mutex_unlock(&lock);
        if(!count){usleep(100000);continue;}
        uint8_t frame[128];int n=wms_scan_frame(&q,frame,sizeof(frame));
        if(n<0||send_all(fd,frame,(size_t)n))break;trace(0,"TX",frame,(size_t)n,q.seq);
        /* DS2208 ACK/NAK mode: queue removal only after host ACK + durable save.
         * Timeout/disconnect keeps the original item; retransmit is not a new scan. */
        uint8_t ack;int result=recv(fd,&ack,1,0);
        if(result!=1)break;trace(0,"RX",&ack,1,q.seq);
        if(ack==0x06){pthread_mutex_lock(&lock);wms_state before=state;
            if(wms_scan_ack(&state,q.seq)||wms_save(directory,&state))state=before;
            pthread_mutex_unlock(&lock);
        }else if(ack!=0x15)break;
    }
}
static void scale(int fd){
    while(is_running()){
        wms_state s;pthread_mutex_lock(&lock);s=state;pthread_mutex_unlock(&lock);
        uint8_t frame[18];int n=wms_scale_frame(&s,frame,sizeof(frame));
        if(n<0||send_all(fd,frame,(size_t)n))break;trace(1,"TX",frame,(size_t)n,0);
        for(int i=0;i<10&&is_running();i++)usleep(100000);
    }
}
static void plc(int fd,int channel){
    while(is_running()){
        uint8_t in[260],out[260];if(read_exact(fd,in,6))break;
        unsigned len=(unsigned)in[4]*256+in[5];if(len<2||len>254||read_exact(fd,in+6,len))break;
        trace(channel,"RX",in,len+6,0);wms_state s;pthread_mutex_lock(&lock);s=state;pthread_mutex_unlock(&lock);
        int n=wms_modbus(&s,channel-1,in,len+6,out,sizeof(out));if(n<0||send_all(fd,out,(size_t)n))break;
        trace(channel,"TX",out,(size_t)n,0);
    }
}
/* RTU-over-TCP byte tunnel: bounded frames, no MBAP, no physical RS485 timing claim. */
static int rtu_exact(int fd,uint8_t *data,size_t n){
    while(n&&is_running()){
        ssize_t k=recv(fd,data,n,0);if(k<0&&errno==EINTR)continue;
        if(k<=0)return -1;data+=k;n-=(size_t)k;
    }return n?-1:0;
}
static void reference_server(int fd,int channel){
    while(is_running()){
        uint8_t in[256],out[260];if(rtu_exact(fd,in,2))break;size_t n=8;
        if(in[1]==0x10||in[1]==0x0f){
            if(rtu_exact(fd,in+2,5))break;n=9+(size_t)in[6];
            if(n>sizeof(in)||rtu_exact(fd,in+7,n-7))break;
        }else if(rtu_exact(fd,in+2,6))break;
        trace(channel,"RX",in,n,0);ee600_state current;vega_state radar;
        pthread_mutex_lock(&lock);current=ee_state;radar=radar_state;pthread_mutex_unlock(&lock);
        int count=channel==5?ee600_rtu(&current,in,n,out,sizeof(out)):vega_rtu(&radar,in,n,out,sizeof(out));
        if(count<0)break;
        if(count&&(send_all(fd,out,(size_t)count)))break;
        if(count)trace(channel,"TX",out,(size_t)count,0);
    }
}
static uint64_t monotonic_ms(void){struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);return (uint64_t)now.tv_sec*1000+(uint64_t)now.tv_nsec/1000000;}
static void sr_record(const scanner_reference *result){
    if(result->phase<SR_ACKED)return;
    char path[1024];snprintf(path,sizeof(path),"%s/strict-handshake.log",directory);
    pthread_mutex_lock(&lock);FILE *f=fopen(path,"ab");
    if(f){fprintf(f,"{\"profile\":\"ds2208.handshake.reference.v1\",\"simulated\":true,\"phase\":%u,\"attempts\":%u,\"max_total_sends\":3,\"timeout_ms\":2000}\n",result->phase,result->attempts);fclose(f);}
    pthread_mutex_unlock(&lock);
}
static void scanner_reference_server(int fd){
    pthread_mutex_lock(&lock);sr_init(&sr_state);sr_accepting=1;pthread_mutex_unlock(&lock);
    for(;;){
        uint8_t frame[100];int n=0;scanner_reference result;
        pthread_mutex_lock(&lock);
        if(!running)sr_end(&sr_state,SR_STOPPED);
        sr_expire(&sr_state,monotonic_ms());result=sr_state;
        if(!running||result.phase>=SR_ACKED){sr_accepting=0;pthread_mutex_unlock(&lock);sr_record(&result);return;}
        if(sr_state.phase==SR_READY)n=sr_frame(&sr_state,frame,sizeof(frame));
        pthread_mutex_unlock(&lock);
        if(n>0){
            int failed=send_all(fd,frame,(size_t)n);if(!failed)trace(7,"TX",frame,(size_t)n,0);
            pthread_mutex_lock(&lock);
            if(failed)sr_end(&sr_state,SR_DISCONNECTED);else if(sr_sent(&sr_state,monotonic_ms()))sr_end(&sr_state,SR_STOPPED);
            pthread_mutex_unlock(&lock);
            if(failed)continue;
        }
        fd_set reads;FD_ZERO(&reads);FD_SET(fd,&reads);struct timeval timeout={0,50000};
        int ready=select(fd+1,&reads,NULL,NULL,&timeout);
        if(ready<0&&errno==EINTR)continue;
        if(ready==0)continue;
        uint8_t reply;ssize_t got=ready<0?-1:recv(fd,&reply,1,0);
        if(got==1)trace(7,"RX",&reply,1,0);
        pthread_mutex_lock(&lock);
        if(got!=1){sr_end(&sr_state,SR_DISCONNECTED);sr_accepting=0;result=sr_state;pthread_mutex_unlock(&lock);sr_record(&result);return;}
        sr_reply(&sr_state,reply,monotonic_ms());pthread_mutex_unlock(&lock);
    }
}
static void *worker(void *arg){
    int ch=(int)(intptr_t)arg;
    while(is_running()){
        pthread_mutex_lock(&lock);int listener=listeners[ch];pthread_mutex_unlock(&lock);
        fd_set f;FD_ZERO(&f);FD_SET(listener,&f);struct timeval wait={0,250000};
        int ready=select(listener+1,&f,NULL,NULL,&wait);if(ready<=0)continue;
        int fd=accept(listener,NULL,NULL);if(fd<0)continue;
        struct timeval timeout={2,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
        pthread_mutex_lock(&lock);connections[ch]++;pthread_mutex_unlock(&lock);
        if(ch==0)scanner(fd);else if(ch==1)scale(fd);else if(ch==7)scanner_reference_server(fd);else if(ch>=5)reference_server(fd,ch);else plc(fd,ch);
        shutdown(fd,SHUT_RDWR);close(fd);
        pthread_mutex_lock(&lock);connections[ch]--;pthread_mutex_unlock(&lock);
    }return NULL;
}
static void stop_all(void){
    pthread_mutex_lock(&lock);int made=worker_count;worker_count=0;running=0;
    for(int i=0;i<CHANNELS;i++)if(listeners[i]>=0)shutdown(listeners[i],SHUT_RDWR);
    pthread_mutex_unlock(&lock);for(int i=0;i<made;i++)pthread_join(workers[i],NULL);
    pthread_mutex_lock(&lock);for(int i=0;i<CHANNELS;i++)if(listeners[i]>=0){close(listeners[i]);listeners[i]=-1;}pthread_mutex_unlock(&lock);
}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeInit(JNIEnv *env,jclass cls,jstring dir){
    (void)cls;const char *d=(*env)->GetStringUTFChars(env,dir,NULL);if(!d)return NULL;
    if(strlen(d)>=sizeof(directory)){(*env)->ReleaseStringUTFChars(env,dir,d);return (*env)->NewStringUTF(env,"状态目录过长");}
    stop_all();pthread_mutex_lock(&lock);strcpy(directory,d);wms_defaults(&state);
    int r=wms_load(directory,&state);initialized=r>=0;
    if(r==1&&wms_save(directory,&state)){initialized=0;r=-1;}
    ee600_defaults(&ee_state);int er=ee600_load(directory,&ee_state);
    if(er==1&&ee600_save(directory,&ee_state))er=-1;
    if(er<0)initialized=0;
    vega_defaults(&radar_state);int vr=vega_load(directory,&radar_state);
    if(vr==1&&vega_save(directory,&radar_state))vr=-1;
    if(vr<0)initialized=0;
    sr_init(&sr_state);sr_accepting=0;
    pthread_mutex_unlock(&lock);(*env)->ReleaseStringUTFChars(env,dir,d);
    return (*env)->NewStringUTF(env,r<0?"状态文件损坏或不可写：已停止，保留文件供核对":er<0?"EE600配置损坏或不可写：保留原队列，服务未启动":vr<0?"VEGA配置损坏或不可写：保留原队列，服务未启动":"C 核心已就绪，待发扫码队列已恢复");
}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeStart(JNIEnv *env,jclass cls,jboolean lan){
    (void)cls;stop_all();pthread_mutex_lock(&lock);
    if(!initialized){pthread_mutex_unlock(&lock);return (*env)->NewStringUTF(env,"状态未就绪，不能启动");}
    int error=0;
    for(int i=0;i<CHANNELS;i++){
        int fd=socket(AF_INET,SOCK_STREAM,0);listeners[i]=fd;if(fd<0){error=1;break;}
        int yes=1;setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&yes,sizeof(yes));
        struct sockaddr_in a;memset(&a,0,sizeof(a));a.sin_family=AF_INET;a.sin_port=htons((uint16_t)ports[i]);a.sin_addr.s_addr=htonl(lan?INADDR_ANY:INADDR_LOOPBACK);
        if(bind(fd,(struct sockaddr*)&a,sizeof(a))||listen(fd,4)){error=1;break;}
    }
    if(error){for(int i=0;i<CHANNELS;i++)if(listeners[i]>=0){close(listeners[i]);listeners[i]=-1;}pthread_mutex_unlock(&lock);return (*env)->NewStringUTF(env,"端口启动失败，请检查其他端口使用方");}
    running=1;pthread_mutex_unlock(&lock);
    for(int i=0;i<CHANNELS;i++){if(pthread_create(&workers[i],NULL,worker,(void*)(intptr_t)i))break;worker_count++;}
    if(worker_count!=CHANNELS){stop_all();return (*env)->NewStringUTF(env,"设备线程启动失败");}
    return (*env)->NewStringUTF(env,lan?"局域网协议服务已启动":"USB／本机协议服务已启动");
}
JNIEXPORT void JNICALL Java_cn_projectb_terminal_MainActivity_nativeStop(JNIEnv *env,jclass cls){(void)env;(void)cls;stop_all();}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeScan(JNIEnv *env,jclass cls,jstring text){
    (void)cls;const char *tag=(*env)->GetStringUTFChars(env,text,NULL);if(!tag)return NULL;
    pthread_mutex_lock(&lock);wms_state old=state;int r=initialized?wms_scan_add(&state,tag):-1;
    if(!r&&wms_save(directory,&state)){state=old;r=-2;}pthread_mutex_unlock(&lock);(*env)->ReleaseStringUTFChars(env,text,tag);
    return (*env)->NewStringUTF(env,r==-2?"保存失败，未加入队列":r?"仅接受 1–96 位可打印 ASCII 标签，队列上限128":"已保存到 C 队列；等待网关读取和 ACK，未确认收货");
}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeConfigure(JNIEnv *env,jclass cls,jdouble gross,jdouble tare,jdouble level,jdouble pressure,jdouble flow,jint flags){
    (void)cls;wms_state temp;pthread_mutex_lock(&lock);temp=state;pthread_mutex_unlock(&lock);
    if(!isfinite(gross)||!isfinite(tare)||!isfinite(level)||!isfinite(pressure)||!isfinite(flow)||gross<0||tare<0||gross>999999||tare>gross||level<0||level>100||pressure<0||flow<0)
        return (*env)->NewStringUTF(env,"参数无效：毛重须不小于皮重，料位0–100%，其余数值非负");
    if(floor(gross)!=gross||floor(tare)!=tare)return (*env)->NewStringUTF(env,"当前仪表配置为整数千克，请输入整数重量");
    pthread_mutex_lock(&lock);temp=state;state.gross_kg=gross;state.tare_kg=tare;state.level_percent=level;state.pressure_kpa=pressure;state.flow_m3h=flow;
    state.stable=(flags&1)!=0;state.occupied=(flags&2)!=0;state.person_present=(flags&4)!=0;state.blockage=(flags&8)!=0;state.leakage=(flags&16)!=0;state.fault=(flags&32)!=0;
    int r=initialized?wms_save(directory,&state):-1;if(r)state=temp;pthread_mutex_unlock(&lock);
    return (*env)->NewStringUTF(env,r?"保存失败，参数未改变":"C 设备模型参数已保存");
}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeSnapshot(JNIEnv *env,jclass cls){
    (void)cls;char b[2000];pthread_mutex_lock(&lock);
    snprintf(b,sizeof(b),"{\"running\":%s,\"queued\":%u,\"next_seq\":%llu,\"gross\":%.0f,\"tare\":%.0f,\"net\":%.0f,\"level\":%.2f,\"pressure\":%.2f,\"flow\":%.2f,\"flags\":%d,\"clients\":[%u,%u,%u,%u,%u,%u],\"ee_pressure_pa\":%.17g,\"ee_k\":%.17g,\"ee_density\":%.17g,\"ee_configured\":%s,\"ee_error\":%s}",
        running?"true":"false",state.count,(unsigned long long)state.next_seq,state.gross_kg,state.tare_kg,state.gross_kg-state.tare_kg,state.level_percent,state.pressure_kpa,state.flow_m3h,
        state.stable|state.occupied<<1|state.person_present<<2|state.blockage<<3|state.leakage<<4|state.fault<<5,connections[0],connections[1],connections[2],connections[3],connections[4],connections[5],
        ee_state.pressure_pa,ee_state.k_factor,ee_state.density_kgm3,ee_state.flow_configured?"true":"false",ee_state.critical_error?"true":"false");
    size_t used=strlen(b);double percent=vega_percent(&radar_state);char value[40];
    if(isfinite(percent))snprintf(value,sizeof(value),"%.8g",percent);else strcpy(value,"null");
    snprintf(b+used-1,sizeof(b)-used+1,",\"vega_pv\":%.17g,\"vega_unit\":%u,\"vega_empty\":%.17g,\"vega_full\":%.17g,\"vega_distance\":%s,\"vega_calibrated\":%s,\"vega_invalid\":%s,\"vega_nonfinite\":%s,\"vega_percent\":%s,\"vega_clients\":%u,\"sr_accepting\":%s,\"sr_phase\":%u,\"sr_attempts\":%u}",
        radar_state.pv,radar_state.unit_code,radar_state.empty_m,radar_state.full_m,radar_state.distance_confirmed?"true":"false",radar_state.calibrated?"true":"false",radar_state.invalid?"true":"false",radar_state.nonfinite?"true":"false",value,connections[6],sr_accepting?"true":"false",sr_state.phase,sr_state.attempts);
    pthread_mutex_unlock(&lock);return (*env)->NewStringUTF(env,b);
}
JNIEXPORT jint JNICALL Java_cn_projectb_terminal_MainActivity_nativeSelfTest(JNIEnv *env,jclass cls){(void)env;(void)cls;int r=wms_selftest();if(r)return r;r=ee600_selftest();if(r)return 100+r;r=vega_selftest();if(r)return 200+r;r=sr_selftest();return r?300+r:0;}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeReferenceScan(JNIEnv *env,jclass cls,jstring text){
    (void)cls;const char *tag=(*env)->GetStringUTFChars(env,text,NULL);if(!tag)return NULL;
    pthread_mutex_lock(&lock);int connected=initialized&&running&&sr_accepting;
    int result=connected?sr_begin(&sr_state,tag):-2;pthread_mutex_unlock(&lock);(*env)->ReleaseStringUTFChars(env,text,tag);
    return (*env)->NewStringUTF(env,result==-2?"请先让参考客户端连接19065，再发送参考标签":result?"参考事务忙，或标签不是1–96位可打印ASCII":"已提交独立握手参考事务；未加入业务队列");
}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeVegaConfigure(JNIEnv *env,jclass cls,jdouble pv,jint unit,jdouble empty,jdouble full,jboolean distance,jboolean calibrated,jboolean invalid,jboolean nonfinite){
    (void)cls;vega_state next;memset(&next,0,sizeof(next));next.pv=pv;next.unit_code=(unsigned)unit;next.empty_m=empty;next.full_m=full;
    next.distance_confirmed=distance?1:0;next.calibrated=calibrated?1:0;next.invalid=invalid?1:0;next.nonfinite=nonfinite?1:0;
    if(!vega_valid(&next))return (*env)->NewStringUTF(env,"VEGA参数无效：PV 0–1000000，单位0–65535，空距须大于满距且不超过10000 m");
    pthread_mutex_lock(&lock);int r=initialized?vega_save(directory,&next):-1;if(!r)radar_state=next;pthread_mutex_unlock(&lock);
    return (*env)->NewStringUTF(env,r?"VEGA保存失败，原参数保留":"VEGA参考参数已保存；未接入现场标定");
}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeEeConfigure(JNIEnv *env,jclass cls,jdouble p,jdouble k,jdouble density,jboolean calibrated,jboolean error){
    (void)cls;ee600_state next;memset(&next,0,sizeof(next));next.pressure_pa=p;next.k_factor=k;next.density_kgm3=density;
    next.flow_configured=calibrated?1:0;next.critical_error=error?1:0;
    if(!ee600_valid(&next))return (*env)->NewStringUTF(env,"EE600参数无效：差压0–20000 Pa，k 0–1000000，密度0.01–100；启用风量时k须大于0");
    pthread_mutex_lock(&lock);int r=initialized?ee600_save(directory,&next):-1;if(!r)ee_state=next;pthread_mutex_unlock(&lock);
    return (*env)->NewStringUTF(env,r?"EE600保存失败，原参数保留":"EE600参考参数已保存；原五通道和扫码队列保留");
}
JNIEXPORT jstring JNICALL Java_cn_projectb_terminal_MainActivity_nativeLog(JNIEnv *env,jclass cls){
    (void)cls;char path[1024],data[60001];snprintf(path,sizeof(path),"%s/raw-frames.log",directory);pthread_mutex_lock(&lock);
    FILE *f=fopen(path,"rb");size_t n=0;if(f){fseek(f,0,SEEK_END);long len=ftell(f);fseek(f,len>60000?len-60000:0,SEEK_SET);n=fread(data,1,60000,f);fclose(f);}data[n]=0;
    pthread_mutex_unlock(&lock);return (*env)->NewStringUTF(env,n?data:"尚无网关连接及原始收发报文");
}
