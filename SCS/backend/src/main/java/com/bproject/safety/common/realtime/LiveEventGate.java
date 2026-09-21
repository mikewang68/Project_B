package com.bproject.safety.common.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 实时事件发布门（Phase B：跨聚合工作流“先全部 save、后统一发布”的唯一收口点）。
 *
 * <p>背景：InMemory 阶段没有数据库事务，但 AI confirm / Edge replay 等跨聚合流程会连续写多个
 * Repository 并在中途广播 LiveEvent，可能出现“alert.new 已推送、AI 事件尚未 save”的半流程广播。
 * 顶层 workflow 用 {@link #buffer(Supplier)} 包裹持久化阶段：期间所有实时出口
 * （DomainLivePublisher / Alert / Ai ChangeNotifier）调用 {@link #emit(Runnable)} 时只入队，
 * 待所有 Repository save 成功后按入队顺序统一执行；任一保存抛异常则 {@code discard}，
 * 不产生任何广播。</p>
 *
 * <p>未开启缓冲时 {@link #emit(Runnable)} 立即执行，单聚合命令行为与此前完全一致。</p>
 *
 * <p>未来 openGauss 阶段，本类是唯一替换点：flush 段改为事务 {@code AFTER_COMMIT} 回调
 * （或 Transactional Outbox → RocketMQ），业务 Service / 状态机无需改动。本阶段不实现 Outbox。</p>
 */
@Component
public class LiveEventGate {

    private static final Logger log = LoggerFactory.getLogger(LiveEventGate.class);

    private static class Context {
        int depth = 0;
        boolean failed = false;
        final List<Runnable> queue = new ArrayList<>();
    }

    private final ThreadLocal<Context> contextHolder = new ThreadLocal<>();

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
        }
        ctx.depth++;
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

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            List<Runnable> pending = new ArrayList<>(ctx.queue);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            for (Runnable broadcast : pending) {
                                try {
                                    broadcast.run();
                                } catch (RuntimeException ex) {
                                    log.warn("buffered live event broadcast failed: {}", ex.getMessage());
                                }
                            }
                        }
                    });
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
