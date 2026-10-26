#ifndef WMS_PROTOCOL_CORE_H
#define WMS_PROTOCOL_CORE_H
#include <stddef.h>
#include <stdint.h>

#define WMS_QUEUE_LIMIT 128
#define WMS_TAG_LIMIT 96
typedef struct { uint64_t seq; char tag[WMS_TAG_LIMIT + 1]; } wms_scan;
typedef struct {
    uint64_t next_seq;
    unsigned count;
    wms_scan queue[WMS_QUEUE_LIMIT];
    double gross_kg, tare_kg, level_percent, pressure_kpa, flow_m3h;
    int stable, fault, occupied, person_present, blockage, leakage;
} wms_state;

void wms_defaults(wms_state *s);
int wms_scan_add(wms_state *s, const char *tag);
int wms_scan_ack(wms_state *s, uint64_t seq);
int wms_scan_frame(const wms_scan *scan, uint8_t *out, size_t cap);
int wms_scale_frame(const wms_state *s, uint8_t *out, size_t cap);
int wms_modbus(const wms_state *s, int device, const uint8_t *in, size_t n,
               uint8_t *out, size_t cap);
uint32_t wms_crc32(const void *data, size_t n);
int wms_save(const char *dir, const wms_state *s);
int wms_load(const char *dir, wms_state *s);
int wms_selftest(void);
#endif
