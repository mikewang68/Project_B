#ifndef WMS_EE600_H
#define WMS_EE600_H
#include <stddef.h>
#include <stdint.h>

/* Independent OEM reference profile. Never append fields to the existing queue ABI. */
typedef struct {
    double pressure_pa, k_factor, density_kgm3;
    int flow_configured, critical_error;
} ee600_state;
void ee600_defaults(ee600_state *s);
int ee600_valid(const ee600_state *s);
double ee600_flow(const ee600_state *s);
uint16_t ee600_crc(const uint8_t *data, size_t n);
/* 0 means silently ignored (bad CRC/address); positive = full RTU response. */
int ee600_rtu(const ee600_state *s,const uint8_t *in,size_t n,uint8_t *out,size_t cap);
int ee600_save(const char *dir,const ee600_state *s);
int ee600_load(const char *dir,ee600_state *s);
int ee600_selftest(void);
#endif
