package com.bproject.safety.config;

import org.springframework.jdbc.core.namedparam.*;

/** Repository-local adapter; the application's datasource and unscoped workers are unchanged. */
public final class ScsScopedJdbcTemplate extends NamedParameterJdbcTemplate {
  public ScsScopedJdbcTemplate(NamedParameterJdbcTemplate original){super(original.getJdbcTemplate());}
  @Override protected ParsedSql getParsedSql(String sql){
    return super.getParsedSql(ScsScopedSql.apply(sql,ScsCentralScope.current()));
  }
}
