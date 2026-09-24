package com.bproject.ehm.workbench.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public record ShiftHandover(
        String handoverNo,
        LocalDate shiftDate,
        String outgoingShift,
        String incomingShift,
        String outgoingLeader,
        String incomingLeader,
        String summary,
        List<String> riskItems,
        List<String> unfinishedItems,
        List<String> equipmentExceptions,
        String notes,
        String status,
        Instant submittedAt,
        Instant receivedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public ShiftHandover {
        riskItems = immutable(riskItems);
        unfinishedItems = immutable(unfinishedItems);
        equipmentExceptions = immutable(equipmentExceptions);
    }

    public static ShiftHandover create(String handoverNo, LocalDate shiftDate, String outgoingShift,
                                       String incomingShift, String outgoingLeader, String incomingLeader,
                                       String summary, List<String> riskItems, List<String> unfinishedItems,
                                       List<String> equipmentExceptions, String notes, Instant now) {
        return new ShiftHandover(normalize(handoverNo), shiftDate == null ? LocalDate.now() : shiftDate,
                fallback(outgoingShift, "白班"), fallback(incomingShift, "夜班"),
                fallback(outgoingLeader, "待确认"), fallback(incomingLeader, "待确认"),
                required(summary, "交接摘要"), riskItems, unfinishedItems, equipmentExceptions,
                trim(notes), "DRAFT", null, null, now, now, null);
    }

    public ShiftHandover revise(LocalDate shiftDate, String outgoingShift, String incomingShift,
                                String outgoingLeader, String incomingLeader, String summary,
                                List<String> riskItems, List<String> unfinishedItems,
                                List<String> equipmentExceptions, String notes, Instant now) {
        if (!"DRAFT".equals(status)) throw new DomainConflictException("只有草稿交接记录允许修改");
        return new ShiftHandover(handoverNo, shiftDate == null ? this.shiftDate : shiftDate,
                fallback(outgoingShift, this.outgoingShift), fallback(incomingShift, this.incomingShift),
                fallback(outgoingLeader, this.outgoingLeader), fallback(incomingLeader, this.incomingLeader),
                required(summary, "交接摘要"), riskItems, unfinishedItems, equipmentExceptions,
                trim(notes), status, submittedAt, receivedAt, createdAt, now, version);
    }

    public ShiftHandover submit(String operator, Instant now) {
        if (!"DRAFT".equals(status)) throw new DomainConflictException("只有草稿可以提交交接");
        return new ShiftHandover(handoverNo, shiftDate, outgoingShift, incomingShift,
                fallback(operator, outgoingLeader), incomingLeader, summary, riskItems, unfinishedItems,
                equipmentExceptions, notes, "SUBMITTED", now, null, createdAt, now, version);
    }

    public ShiftHandover receive(String operator, Instant now) {
        if (!"SUBMITTED".equals(status)) throw new DomainConflictException("只有已提交记录可以接班确认");
        return new ShiftHandover(handoverNo, shiftDate, outgoingShift, incomingShift,
                outgoingLeader, fallback(operator, incomingLeader), summary, riskItems, unfinishedItems,
                equipmentExceptions, notes, "RECEIVED", submittedAt, now, createdAt, now, version);
    }

    private static List<String> immutable(List<String> value) { return value == null ? List.of() : value.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList(); }
    private static String normalize(String value) { return required(value, "交接编号").toUpperCase(Locale.ROOT); }
    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }
    private static String fallback(String value, String replacement) { return value == null || value.isBlank() ? replacement : value.trim(); }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
