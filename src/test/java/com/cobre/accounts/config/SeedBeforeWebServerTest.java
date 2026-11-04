package com.cobre.accounts.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.FreshDatabase;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Review of T04, finding 8: the seed runs before the web server accepts connections, so no request
 * ever sees the service without its accounts (spec §6.3, §8.2). {@link WebServerInitializedEvent}
 * is published right after the connector starts; by then the seed must already be committed. This
 * fails if the seed goes back to an {@code ApplicationRunner}, which runs after the server starts.
 */
class SeedBeforeWebServerTest {

    @Test
    void seedIsCommittedBeforeTheConnectorAcceptsConnections() {
        FreshDatabase db = FreshDatabase.create();
        AtomicInteger accountsWhenServerStarted = new AtomicInteger(-1);
        AtomicInteger linesWhenServerStarted = new AtomicInteger(-1);
        // An anonymous class, not a lambda: Spring needs the generic type to deliver only this event.
        ApplicationListener<WebServerInitializedEvent> listener = new ApplicationListener<>() {
            @Override
            public void onApplicationEvent(WebServerInitializedEvent event) {
                JdbcClient jdbc = JdbcClient.create(db.appDataSource());
                accountsWhenServerStarted.set(jdbc.sql("SELECT count(*) FROM account").query(Integer.class).single());
                linesWhenServerStarted.set(jdbc.sql("SELECT count(*) FROM entry_line").query(Integer.class).single());
            }
        };

        try (ApiApp ignored = ApiApp.start(db, "classpath:fixtures/F01-case-event/seed.json", List.of(listener))) {
            // Case seed: 2 CLIENT accounts + 2 Cobre accounts per currency; one opening of 2 lines.
            assertThat(accountsWhenServerStarted).hasValue(6);
            assertThat(linesWhenServerStarted).hasValue(2);
        }
    }
}
