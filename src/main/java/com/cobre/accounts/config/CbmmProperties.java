package com.cobre.accounts.config;

import com.cobre.accounts.domain.model.CurrencyCode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code cbmm.*} properties of spec §11. Each task adds the ones it wires. */
@ConfigurationProperties("cbmm")
public record CbmmProperties(
        @DefaultValue Seed seed, @DefaultValue({"MXN", "USD"}) List<String> currencies, @DefaultValue Accounts accounts,
        @DefaultValue Processing processing) {

    public record Seed(@DefaultValue("classpath:seed/accounts.json") String path) {}

    /** @param autoCreate D7: {@code cbmm.accounts.auto-create}, off by default */
    public record Accounts(@DefaultValue("false") boolean autoCreate) {}

    /** @param maxConcurrency D5: events decided at once, always below the pool size (spec §7.4) */
    public record Processing(@DefaultValue("16") int maxConcurrency) {}

    public Set<CurrencyCode> currencyCodes() {
        return currencies.stream().map(String::trim).map(CurrencyCode::of).collect(Collectors.toUnmodifiableSet());
    }
}
