package com.bproject.safety.config;

import java.util.Optional;

/**
 * 当前请求的已认证操作人（会话主体）。
 *
 * <p>背景：审计字段（复核人 / 处置人 / 接单人）此前一律取演示用户或前端写死的人名，
 * 与真实登录人无关。本类由 {@link SecurityInterceptor} 在鉴权通过后写入，
 * 由 {@code AlertService} / {@code AiEventService} 作为"请求体未指定操作人"时的兜底来源。
 *
 * <p>生命周期与请求线程绑定：拦截器 {@code afterCompletion} 必须调用 {@link #clear()}，
 * 否则线程池复用会把上一个请求的主体泄漏给下一个请求。无请求上下文（定时任务、测试、
 * Demo 模式）时返回 empty，调用方继续回落到演示用户。
 *
 * <p>与 {@code ScsSessionRegistry} 一致，采用静态持有而非注入：审计兜底被大量 Service
 * 构造链间接使用，注入会波及全部既有测试的构造方式。
 */
public final class ScsRequestActor {

  private static final ThreadLocal<String> SUBJECT = new ThreadLocal<>();
  private static final ThreadLocal<String> DISPLAY_NAME = new ThreadLocal<>();

  private ScsRequestActor() {}

  /** 记录当前请求的操作人；主体为空时忽略（例如边缘节点机器凭据）。 */
  public static void set(String subject, String displayName) {
    if (subject == null || subject.isBlank()) {
      clear();
      return;
    }
    SUBJECT.set(subject);
    DISPLAY_NAME.set(displayName == null || displayName.isBlank() ? subject : displayName);
  }

  public static Optional<String> subject() {
    return Optional.ofNullable(SUBJECT.get());
  }

  /** 审计展示名：优先绑定文件里的本地显示名，缺省回落到主体标识。 */
  public static Optional<String> displayName() {
    return Optional.ofNullable(DISPLAY_NAME.get());
  }

  public static void clear() {
    SUBJECT.remove();
    DISPLAY_NAME.remove();
  }
}
