package com.cobre.accounts.config;

import com.cobre.accounts.adapters.out.persistence.JdbcAccountReadModel;
import com.cobre.accounts.adapters.out.persistence.JdbcAccountRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcJournalRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcSettlementDecisionRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcUnitOfWork;
import com.cobre.accounts.adapters.out.persistence.SeedLoader;
import com.cobre.accounts.application.port.out.AccountReadModel;
import com.cobre.accounts.application.port.out.AccountRepository;
import com.cobre.accounts.application.port.out.JournalRepository;
import com.cobre.accounts.application.port.out.SettlementDecisionRepository;
import com.cobre.accounts.application.port.out.UnitOfWork;
import com.cobre.accounts.domain.model.EntryId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistence adapters (spec §9), the UnitOfWork with retries (§7.3) and the seed at startup (§6.3). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CbmmProperties.class)
class PersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(PersistenceConfig.class);

    @Bean
    AccountRepository accountRepository(JdbcClient jdbc) {
        return new JdbcAccountRepository(jdbc);
    }

    @Bean
    AccountReadModel accountReadModel(JdbcClient jdbc) {
        return new JdbcAccountReadModel(jdbc);
    }

    @Bean
    JournalRepository journalRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        return new JdbcJournalRepository(jdbc, jdbcTemplate);
    }

    @Bean
    SettlementDecisionRepository settlementDecisionRepository(JdbcClient jdbc) {
        return new JdbcSettlementDecisionRepository(jdbc);
    }

    @Bean
    UnitOfWork unitOfWork(PlatformTransactionManager transactionManager) {
        return new JdbcUnitOfWork(new TransactionTemplate(transactionManager),
                JdbcUnitOfWork.Sleeper.threadSleep(), () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    Supplier<EntryId> entryIds() {
        return () -> new EntryId(UUID.randomUUID());
    }

    @Bean
    SeedLoader seedLoader(AccountRepository accounts, JournalRepository journal, UnitOfWork unitOfWork,
            CbmmProperties properties, Supplier<EntryId> entryIds) {
        return new SeedLoader(accounts, journal, unitOfWork, properties.currencyCodes(), entryIds);
    }

    /**
     * Runs after Flyway and before the web server opens its port (T04 review, finding 8): every
     * singleton, the Flyway initializer included, exists by the time {@code afterSingletonsInstantiated}
     * runs, and the server's connector only starts afterwards, in the lifecycle phase of the refresh.
     * So no request ever sees the service without its seed. An exception here stops the service,
     * with its message in the log (spec §6.3). {@code SeedBeforeWebServerTest} fails if this moves.
     */
    @Bean
    SmartInitializingSingleton seedRunner(SeedLoader seedLoader, CbmmProperties properties, ResourceLoader resources) {
        return () -> {
            String path = properties.seed().path();
            Resource seed = resources.getResource(path);
            try (InputStream in = seed.getInputStream()) {
                SeedLoader.SeedResult result = seedLoader.load(in, path);
                log.info("Seed {} loaded: {} client account(s) created, {} opening entr(ies)",
                        path, result.createdClientAccounts(), result.openingEntries());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read seed " + path, e);
            }
        };
    }
}
