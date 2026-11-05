package com.bproject.safety.support.jdbc;

import com.bproject.safety.config.ScsCentralScope;
import com.bproject.safety.config.ScsScopedJdbcTemplate;
import org.mockito.AdditionalAnswers;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.mockito.Mockito.*;

/**
 * Keeps legacy repository SQL/row-mapping tests at their named-parameter boundary.
 * Only these no-context tests mock the adapter construction; real SQL rewriting
 * and regional isolation remain covered by ScsScopedSqlTest with its real H2 JDBC stack.
 */
public final class LegacyRepositoryJdbcFixture implements AutoCloseable {
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final MockedConstruction<ScsScopedJdbcTemplate> adapters;

    public LegacyRepositoryJdbcFixture() {
        requireNoContext();
        when(named.getJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
        var delegate = AdditionalAnswers.delegatesTo(named);
        adapters = mockConstruction(ScsScopedJdbcTemplate.class,
                withSettings().defaultAnswer(invocation -> {
                    requireNoContext();
                    return delegate.answer(invocation);
                }), (adapter, construction) -> {
                    if (construction.arguments().size() != 1 || construction.arguments().get(0) != named)
                        throw new AssertionError("Repository fixture cannot replace an unrelated JDBC adapter");
                });
    }

    public NamedParameterJdbcTemplate named() {
        return named;
    }

    private static void requireNoContext() {
        if (ScsCentralScope.current() != null)
            throw new AssertionError("Use the real JDBC adapter for scoped authorization tests");
    }

    @Override
    public void close() {
        adapters.close();
    }
}
