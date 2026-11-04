package com.cobre.accounts.config;

import com.cobre.accounts.adapters.out.events.LoggingDomainEventPublisher;
import com.cobre.accounts.application.port.in.PostSettlementUseCase;
import com.cobre.accounts.application.port.out.AccountRepository;
import com.cobre.accounts.application.port.out.DomainEventPublisher;
import com.cobre.accounts.application.port.out.JournalRepository;
import com.cobre.accounts.application.port.out.SettlementDecisionRepository;
import com.cobre.accounts.application.port.out.UnitOfWork;
import com.cobre.accounts.application.service.PostSettlementService;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.service.PostingService;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Use case of one event (spec §7.3), its domain service with D7, and the domain event publisher (§8.3). */
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
    PostSettlementUseCase postSettlementUseCase(AccountRepository accounts, JournalRepository journal,
            SettlementDecisionRepository decisions, UnitOfWork unitOfWork, PostingService postingService,
            DomainEventPublisher publisher) {
        return new PostSettlementService(accounts, journal, decisions, unitOfWork, postingService, publisher);
    }
}
