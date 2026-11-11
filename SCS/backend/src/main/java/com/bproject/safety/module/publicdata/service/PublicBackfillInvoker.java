package com.bproject.safety.module.publicdata.service;

/** 手动补读一页历史（backfill-enabled 时由 PublicHistoryBackfill 实现）。 */
public interface PublicBackfillInvoker {

    int backfillNow() throws Exception;
}
