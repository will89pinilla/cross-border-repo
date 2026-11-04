package com.cobre.accounts.config;

import com.cobre.accounts.adapters.out.events.LoggingDomainEventPublisher;
import com.cobre.accounts.adapters.out.persistence.PostgresLoadLock;
import com.cobre.accounts.application.port.in.LoadEventFilesUseCase;
import com.cobre.accounts.application.port.in.PostSettlementUseCase;
import com.cobre.accounts.application.port.out.AccountReadModel;
import com.cobre.accounts.application.port.out.AccountRepository;
import com.cobre.accounts.application.port.out.DomainEventPublisher;
import com.cobre.accounts.application.port.out.JournalRepository;
import com.cobre.accounts.application.port.out.LoadLock;
import com.cobre.accounts.application.port.out.SettlementDecisionRepository;
import com.cobre.accounts.application.port.out.UnitOfWork;
import com.cobre.accounts.application.service.LoadEventFilesService;
import com.cobre.accounts.application.service.PostSettlementService;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.service.PostingService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Use cases of the load and of one event (spec §7.3), the load lock, the domain service with D7,
 * and the domain event publisher (§8.3).
 */
@Configuration(proxyBeanMethods = false)
class ApplicationConfig {

    @Bean
    PostingService postingService(Supplier<EntryId> entryIds, CbmmProperties properties) {
        return new PostingService(entryIds, properties.accounts().autoCreate());
    }

    @Bean
    DomainEventPublisher domainEventPublisher() {
        return new LoggingDomainEventPublisher();
    }

    @Bean
    LoadLock loadLock(DataSource dataSource) {
        return new PostgresLoadLock(dataSource);
    }

    /**
     * Spec §7.4: {@code 1 <= max-concurrency < maximum-pool-size}. Strictly below: besides one
     * connection per event in flight, the load lock holds one for the whole load.
     */
    @Bean
    LoadEventFilesUseCase loadEventFilesUseCase(PostSettlementUseCase postSettlement, LoadLock loadLock,
            AccountReadModel readModel, CbmmProperties properties, DataSource dataSource) {
        int maxConcurrency = properties.processing().maxConcurrency();
        if (maxConcurrency < 1) {
            throw new IllegalStateException("cbmm.processing.max-concurrency must be at least 1, got " + maxConcurrency);
        }
        if (dataSource instanceof HikariDataSource hikari && maxConcurrency >= hikari.getMaximumPoolSize()) {
            throw new IllegalStateException("cbmm.processing.max-concurrency (" + maxConcurrency
                    + ") must be below spring.datasource.hikari.maximum-pool-size (" + hikari.getMaximumPoolSize()
                    + "): the load lock holds one more connection");
        }
        return new LoadEventFilesService(postSettlement, loadLock, readModel, maxConcurrency, Clock.systemUTC(),
                UUID::randomUUID);
    }

    @Bean
    PostSettlementUseCase postSettlementUseCase(AccountRepository accounts, JournalRepository journal,
            SettlementDecisionRepository decisions, UnitOfWork unitOfWork, PostingService postingService,
            DomainEventPublisher publisher) {
        return new PostSettlementService(accounts, journal, decisions, unitOfWork, postingService, publisher);
    }
}
