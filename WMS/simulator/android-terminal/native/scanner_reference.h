#ifndef WMS_SCANNER_REFERENCE_H
#define WMS_SCANNER_REFERENCE_H
#include <stddef.h>
#include <stdint.h>
enum { SR_IDLE=0,SR_READY,SR_WAIT,SR_ACKED,SR_NAK_LIMIT,SR_TIMEOUT,SR_DISCONNECTED,SR_STOPPED };
typedef struct {
    char tag[97];
    unsigned attempts,phase;
    uint64_t deadline_ms;
} scanner_reference;
/* Explicit interpretation: three total sends including the first, not three retries.
 * MN-002874-14EN p9-14 does not explicitly say whether the initial send is counted.
 * Physical firmware confirmation remains pending; no exact OEM-count claim. */
#define SR_MAX_SENDS 3
#define SR_TIMEOUT_MS 2000
void sr_init(scanner_reference *s);
int sr_begin(scanner_reference *s,const char *tag);
int sr_frame(const scanner_reference *s,uint8_t *out,size_t cap);
int sr_sent(scanner_reference *s,uint64_t now_ms);
int sr_reply(scanner_reference *s,uint8_t byte,uint64_t now_ms);
void sr_expire(scanner_reference *s,uint64_t now_ms);
void sr_end(scanner_reference *s,unsigned reason);
int sr_selftest(void);
#endif
