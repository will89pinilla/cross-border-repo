package com.cobre.accounts.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.AccountsServiceApplication;
import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.application.port.in.PostSettlementUseCase;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import com.cobre.accounts.application.port.out.AccountRepository;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.support.FreshDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** D7 through the real Spring wiring: {@code cbmm.accounts.auto-create} reaches the PostingService (AC06, AC15). */
class PostSettlementWiringTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final AccountId UNKNOWN_DESTINATION = AccountId.of("ACC555000001");

    private static ConfigurableApplicationContext start(String... extraArgs) {
        FreshDatabase db = FreshDatabase.create();
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=accounts_app",
                "--spring.datasource.password=app_test_pw",
                "--spring.flyway.user=accounts_owner",
                "--spring.flyway.password=owner_test_pw",
                "--cbmm.seed.path=classpath:fixtures/F08-unknown-account/seed.json"));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(AccountsServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private static ReadEvent firstEventOfF08() throws IOException {
        try (InputStream in = PostSettlementWiringTest.class.getResourceAsStream(
                "/fixtures/F08-unknown-account/loads/1/F08.json")) {
            EventFileReader reader = new EventFileReader(
                    new CbmmEventTranslator(Set.of(CurrencyCode.of("MXN"), USD)));
            return reader.read("F08.json", in.readAllBytes()).events().getFirst();
        }
    }

    @Test
    void AC06_autoCreateIsOffByDefault() throws IOException {
        try (ConfigurableApplicationContext context = start()) {
            SettlementOutcome outcome = context.getBean(PostSettlementUseCase.class).post(firstEventOfF08(), UUID.randomUUID());

            assertThat(outcome).isEqualTo(
                    new SettlementOutcome.Rejected(RejectionCode.ACCOUNT_NOT_FOUND, "DESTINATION", true));
            assertThat(context.getBean(AccountRepository.class).findById(UNKNOWN_DESTINATION)).isEmpty();
        }
    }

    @Test
    void AC15_autoCreatePropertyCreatesTheDestinationInUsd() throws IOException {
        try (ConfigurableApplicationContext context = start("--cbmm.accounts.auto-create=true")) {
            SettlementOutcome outcome = context.getBean(PostSettlementUseCase.class).post(firstEventOfF08(), UUID.randomUUID());

            assertThat(outcome).isInstanceOf(SettlementOutcome.Applied.class);
            assertThat(context.getBean(AccountRepository.class).balanceOf(UNKNOWN_DESTINATION))
                    .contains(Money.of("50.00", USD));
        }
    }
}
