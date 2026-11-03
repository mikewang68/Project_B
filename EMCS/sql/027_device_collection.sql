-- Compatible, additive tables. Register real devices separately after owner approval.
BEGIN;
CREATE TABLE emcs_collect_device (
 device_id varchar(64) PRIMARY KEY, equipment_code varchar(32) NOT NULL,
 area_code varchar(32) NOT NULL, simulated smallint NOT NULL CHECK(simulated IN(0,1)),
 enabled smallint NOT NULL DEFAULT 1 CHECK(enabled IN(0,1))
);
CREATE TABLE emcs_collect_event (
 event_id varchar(240) PRIMARY KEY, device_id varchar(64) NOT NULL REFERENCES emcs_collect_device(device_id),
 wire_sha256 varchar(64) NOT NULL, wire_json text NOT NULL,
 observed_ts_ms bigint NOT NULL, source_ts_ms bigint, received_ts_ms bigint NOT NULL,
 simulated smallint NOT NULL, point_count int NOT NULL
);
CREATE INDEX emcs_collect_event_time ON emcs_collect_event(observed_ts_ms,event_id);
CREATE TABLE emcs_collect_reading (
 event_id varchar(240) NOT NULL REFERENCES emcs_collect_event(event_id),
 point_code varchar(64) NOT NULL, value_json text NOT NULL, unit varchar(32) NOT NULL,
 quality_code varchar(16) NOT NULL,
 PRIMARY KEY(event_id,point_code)
);
-- Simulation registrations are explicit and never stand for real plant devices.
INSERT INTO emcs_collect_device VALUES('SIM-IMG-10','EQ-IMG-10','AREA-A',1,1);
INSERT INTO emcs_collect_device VALUES('SIM-IMG-13','EQ-IMG-13','AREA-A',1,1);
INSERT INTO emcs_collect_device VALUES('SIM-IMG-14','EQ-IMG-14','AREA-A',1,1);
INSERT INTO emcs_collect_device VALUES('SIM-IMG-15','EQ-IMG-15','AREA-A',1,1);
INSERT INTO emcs_collect_device VALUES('SIM-EXT-04','EQ-EXT-04','AREA-A',1,1);
INSERT INTO emcs_collect_device VALUES('SIM-EXT-05','EQ-EXT-05','AREA-A',1,1);
COMMIT;
