# EE600 OEM reference profile (APK 0.3.0)

This is a separate read-only reference for the EE600-HV53J3 differential-pressure subdevice, based on E+E BA_EE600 v1.13 (07-2026), pp20-24 and 27-28. It is not a complete air-slide controller or a commissioned instrument. The existing five channels and device-points.json remain unchanged.

The Android **仪表** tab controls this profile. The existing **工艺** tab continues to control the laboratory PLC values. EE600 settings are stored in a separate CRC-protected ee600-state.bin; the original protocol-state.bin and scanner queue format are unchanged. Corrupted files are preserved and services are not started automatically.

## Connection

- TCP19063 carries complete Modbus RTU frames including CRC16, without an MBAP header. Station address43 (0x2B), FC03/04 reads.
- Physical RS485 timing, baud rate, parity, wiring and adapter behavior are not emulated by a TCP tunnel. The reference hardware default is 8E1 with baud rate as ordered.
- Each connection serves one client, with a 2-second idle/partial-frame receive timeout. Separate consecutive connections are supported.
- FC05/06/0F/10 writes return exception01, unknown registers return02, invalid quantities return03. Invalid CRC, other station addresses and broadcasts are ignored. This limited profile does not implement every OEM function or parameter.
- PDU addresses below already start at zero. Do not subtract one again.

| Register number (1 based) | PDU address | Length | Type / unit |
|---|---|---|---|
| 1215 | 0x04BE | 2 | Float32 CDAB, differential pressure Pa |
| 1217 | 0x04C0 | 2 | Float32 CDAB, differential pressure kPa |
| 1055 | 0x041E | 2 | Float32 CDAB, configured air volume flow m³/h |
| 4108 | 0x100B | 1 | Int16, differential pressure Pa |
| 4109 | 0x100C | 1 | Int16 / 1000, differential pressure kPa |

Read both float registers in one operation. The normal default reproduces the manual's exact frame:

```text
Request : 2B 04 04 BE 00 02 17 15
Response: 2B 04 04 FB A5 43 F8 61 F3
CDAB -> IEEE754 43 F8 FB A5 -> 497.9659729 Pa
```

## Measurement and quality limits

The simulation models a 0..10000Pa range. UI input may reach20000Pa to exercise clipping at10000Pa. Int16 rounding uses nearest-integer rounding as a declared simulation choice. Critical-error mode produces Float32 NaN and Int16 0x8000; the reference client records null/BAD and keeps raw bytes rather than serializing NaN into JSON.

Flow is unavailable until the user explicitly enables the calibrated-flow checkbox and supplies positive k and air density. Before that, 0x041E is deliberately unimplemented and returns exception02. This is the simulator's missing-parameter boundary, not a claim about the factory's default unconfigured response. With valid parameters, q = k × sqrt(delta_p_Pa) × sqrt(1.204/rho), in m³/h. The 400Pa, k18, rho1.204 example gives360m³/h; these are laboratory parameters.

EE600-STATUS-01 remains unresolved: manual p21 lists602/0x259 while p23 lists609/0x260. Both addresses are disabled. Finite and clamped values are therefore UNCERTAIN; the client cannot establish OEM status from this profile. The pressure reference ports and fan calibration remain uncommissioned. No 110-point mapping is promoted to GOOD, and no current pipeline address is replaced.

## Read from a PC

Install the APK on an idle Android emulator or a user-operated phone. Select **连接 → 启动 USB／仅本机服务** and explicitly select its ADB serial:

```powershell
adb -s emulator-5554 forward tcp:19063 tcp:19063
python ee600_client.py --host 127.0.0.1 --port 19063 --read-flow --output runtime/ee600-first
# Remove only this task's forward after use:
adb -s emulator-5554 forward --remove tcp:19063
```

Use a new output directory each time. ee600-readback.json includes request/response bytes, values, units, observed time and quality. Its schema is ee600.reference.v1, deliberately separate from b.device-event.v1 until a confirmed business mapping is available. The client has no public-base or WMS submission code.

OEM documents: [BA_EE600](https://www.epluse.com/fileadmin/data/product/ee600/BA_EE600.pdf), [AN0103 Rev1.4](https://www.epluse.com/fileadmin/data/product/ee071/AN0103.pdf). Documentation dates identify the fixed reference, not the user's actual firmware.
