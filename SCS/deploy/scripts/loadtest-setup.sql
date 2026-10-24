-- SCS 压测环境初始化：scs_test 库 + 授权（配合 loadtest-seed.sql；重建时先 DROP DATABASE scs_test）
CREATE DATABASE scs_test WITH ENCODING 'UTF8';
GRANT CONNECT ON DATABASE scs_test TO safety_admin;
