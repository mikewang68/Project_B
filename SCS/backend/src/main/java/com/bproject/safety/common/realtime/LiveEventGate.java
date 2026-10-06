package com.bproject.safety.common.realtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 实时事件发布门（Phase B / Phase 1：跨聚合工作流“先全部 save、后统一发布”的唯一收口点）。
 *
 * <p>背景：跨聚合流程连续写多个 Repository 并在中途广播 LiveEvent。
 * 顶层 workflow 用 {@link #buffer(Supplier)} 包裹持久化阶段：期间所有实时出口调用
 * {@link #emit(Runnable)} 时只入队；待事务提交成功（afterCommit）统一执行；
 * 事务回滚或保存抛异常则丢弃，不产生任何广播。</p>
 *
 * <p>特性保证：
 * <ul>
 *   <li>无事务：最外层 {@link #buffer} 执行成功后立即发布；</li>
 *   <li>有事务：最外层 {@link #buffer} 成功后延迟至事务 {@code AFTER_COMMIT} 统一发布；</li>
 *   <li>事务回滚 / 异常：缓冲事件彻底丢弃，绝不广播；</li>
 *   <li>嵌套事务（REQUIRES_NEW）：内层事务与外层事务的上下文、发布时机与回滚隔离，互不污染；</li>
 *   <li>同线程连续事务：无 ThreadLocal 泄漏，事务完成后状态彻底清理。</li>
 * </ul>
 * </p>
 */
@Component
public class LiveEventGate {

    private static final Logger log = LoggerFactory.getLogger(LiveEventGate.class);

    private static class Context {
        int depth = 0;
        boolean failed = false;
        boolean flushed = false;
        boolean syncRegistered = false;
        final List<Runnable> queue = new ArrayList<>();
    }

    private final ThreadLocal<Context> contextHolder = new ThreadLocal<>();
    private final ThreadLocal<Deque<Context>> suspendedHolder = ThreadLocal.withInitial(ArrayDeque::new);

    /** 当前线程是否处于缓冲区间。 */
    public boolean isBuffering() {
        Context ctx = contextHolder.get();
        return ctx != null && ctx.depth > 0;
    }

    /**
     * 广播出口统一入口：缓冲时入队（不执行），否则立即执行。
     * 广播本身保持 best-effort（具体出口内部已吞掉发送异常）。
     */
    public void emit(Runnable broadcast) {
        Context ctx = contextHolder.get();
        if (ctx == null || ctx.depth <= 0) {
            broadcast.run();
        } else if (!ctx.failed) {
            ctx.queue.add(broadcast);
        }
    }

    /** 开始缓冲（与 {@link #flush()} / {@link #discard()} 配对，推荐直接用 {@link #buffer}）。 */
    public void begin() {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            ctx = new Context();
            contextHolder.set(ctx);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                registerTransactionSynchronization(ctx);
            }
        }
        ctx.depth++;
    }

    private void registerTransactionSynchronization(Context ctx) {
        ctx.syncRegistered = true;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void suspend() {
                Context current = contextHolder.get();
                if (current != null) {
                    suspendedHolder.get().push(current);
                    contextHolder.remove();
                }
            }

            @Override
            public void resume() {
                Deque<Context> suspended = suspendedHolder.get();
                if (!suspended.isEmpty()) {
                    contextHolder.set(suspended.pop());
                }
            }

            @Override
            public void afterCommit() {
                if (!ctx.failed && ctx.flushed) {
                    List<Runnable> pending = new ArrayList<>(ctx.queue);
                    for (Runnable broadcast : pending) {
                        try {
                            broadcast.run();
                        } catch (RuntimeException ex) {
                            log.warn("buffered live event broadcast failed: {}", ex.getMessage());
                        }
                    }
                }
            }

            @Override
            public void afterCompletion(int status) {
                ctx.queue.clear();
                contextHolder.remove();
                Deque<Context> stack = suspendedHolder.get();
                if (stack != null && stack.isEmpty()) {
                    suspendedHolder.remove();
                }
            }
        });
    }

    /** 按入队顺序执行缓冲的全部广播，然后清空缓冲区（仅最外层退出时统一执行）。 */
    public void flush() {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            return;
        }
        ctx.depth = Math.max(0, ctx.depth - 1);
        if (ctx.depth > 0) {
            return;
        }
        contextHolder.remove();
        if (ctx.failed) {
            return;
        }

        if (ctx.syncRegistered && TransactionSynchronizationManager.isActualTransactionActive()) {
            ctx.flushed = true;
        } else {
            for (Runnable broadcast : ctx.queue) {
                try {
                    broadcast.run();
                } catch (RuntimeException ex) {
                    // 广播失败不得影响已完成的业务写结果（best-effort 语义不变）
                    log.warn("buffered live event broadcast failed: {}", ex.getMessage());
                }
            }
        }
    }

    /** 丢弃缓冲区间内全部广播（持久化阶段失败时调用，传播至根上下文）。 */
    public void discard() {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            return;
        }
        ctx.failed = true;
        ctx.queue.clear();
        ctx.depth = Math.max(0, ctx.depth - 1);
        if (ctx.depth <= 0) {
            contextHolder.remove();
        }
    }

    /**
     * 包裹一个跨聚合持久化阶段：action 内的全部实时广播延迟到所有 save 成功后统一发布；
     * action 抛异常则丢弃全部缓冲广播并原样抛出。
     */
    public <T> T buffer(Supplier<T> action) {
        begin();
        try {
            T result = action.get();
            flush();
            return result;
        } catch (RuntimeException ex) {
            discard();
            throw ex;
        } catch (Error err) {
            discard();
            throw err;
        }
    }

    /** 无返回值版本。 */
    public void buffer(Runnable action) {
        buffer(() -> {
            action.run();
            return null;
        });
    }
}
