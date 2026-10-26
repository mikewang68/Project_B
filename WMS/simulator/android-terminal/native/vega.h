#ifndef WMS_VEGA_H
#define WMS_VEGA_H
#include <stddef.h>
#include <stdint.h>
typedef struct {
    double pv, empty_m, full_m;
    unsigned unit_code;
    int distance_confirmed, calibrated, invalid, nonfinite;
} vega_state;
void vega_defaults(vega_state *s);
int vega_valid(const vega_state *s);
double vega_percent(const vega_state *s);
int vega_rtu(const vega_state *s,const uint8_t *in,size_t n,uint8_t *out,size_t cap);
int vega_save(const char *dir,const vega_state *s);
int vega_load(const char *dir,vega_state *s);
int vega_selftest(void);
#endif
