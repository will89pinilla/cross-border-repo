package com.cobre.accounts.config;

import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.application.port.out.AccountReadModel;
import com.cobre.accounts.application.service.AccountQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the REST adapter needs (spec §8.2): the file reader with {@code cbmm.currencies} (rule 2,
 * §6.1) and the queries. The controllers and the error handler are found by component scan: Spring
 * MVC maps {@code @RestController} beans, and declaring them here too would register them twice.
 */
@Configuration(proxyBeanMethods = false)
class RestConfig {

    @Bean
    EventFileReader eventFileReader(CbmmProperties properties) {
        return new EventFileReader(new CbmmEventTranslator(properties.currencyCodes()));
    }

    /** One instance implements both query ports, {@code GetBalanceQuery} and {@code GetTransactionsQuery}. */
    @Bean
    AccountQueryService accountQueryService(AccountReadModel readModel) {
        return new AccountQueryService(readModel);
    }
}
