-- Target: openGauss 6.0.5, independent mt_wms database.
CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(32) PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    installed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    success BOOLEAN NOT NULL
);

COMMENT ON TABLE schema_version IS 'MT-WMS数据库脚本执行记录';
