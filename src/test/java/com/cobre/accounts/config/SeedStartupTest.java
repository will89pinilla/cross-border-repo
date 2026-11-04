package com.cobre.accounts.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.AccountsServiceApplication;
import com.cobre.accounts.adapters.out.persistence.SeedConfigurationException;
import com.cobre.accounts.support.FreshDatabase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec §6.3 through the real Spring wiring: {@code cbmm.seed.path} and {@code cbmm.currencies}
 * reach the SeedLoader, and an invalid seed stops the service with its reason in the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class SeedStartupTest {

    private static ConfigurableApplicationContext start(FreshDatabase db, String seedPath, String currencies) {
        return new SpringApplicationBuilder(AccountsServiceApplication.class)
                .web(WebApplicationType.NONE)
                // Command-line args: they override application.yml (builder properties() would not).
                .run(
                        "--spring.datasource.url=" + db.url(),
                        "--spring.datasource.username=accounts_app",
                        "--spring.datasource.password=app_test_pw",
                        "--spring.flyway.user=accounts_owner",
                        "--spring.flyway.password=owner_test_pw",
                        "--cbmm.seed.path=" + seedPath,
                        "--cbmm.currencies=" + currencies);
    }

    @Test
    void seedPathAndCurrenciesComeFromConfiguration() {
        FreshDatabase db = FreshDatabase.create();

        try (ConfigurableApplicationContext ignored = start(db, "classpath:seed/eur-account.json", "EUR")) {
            JdbcClient jdbc = JdbcClient.create(db.appDataSource());
            List<String> ids = jdbc.sql("SELECT account_id FROM account ORDER BY account_id COLLATE \"C\"")
                    .query(String.class).list();
            assertThat(ids).containsExactly("ACC_EUR", "COBRE_FUNDING_EUR", "COBRE_FX_POSITION_EUR");
            assertThat(jdbc.sql("SELECT balance FROM account WHERE account_id = 'ACC_EUR'")
                    .query(BigDecimal.class).single()).isEqualByComparingTo("25.00");
        }
    }

    @Test
    void cobreIdInSeedStopsTheServiceAndSaysWhyInTheLog(CapturedOutput output) {
        FreshDatabase db = FreshDatabase.create();

        assertThatThrownBy(() -> start(db, "classpath:seed/cobre-id.json", "MXN,USD").close())
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e))
                        .isInstanceOf(SeedConfigurationException.class)
                        .hasMessageContaining("COBRE_FX_POSITION_MXN"));

        assertThat(output).contains("COBRE_FX_POSITION_MXN").contains("reserved for Cobre");
        assertThat(JdbcClient.create(db.appDataSource()).sql("SELECT count(*) FROM account")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void currencyOutsideConfiguredCurrenciesStopsTheService(CapturedOutput output) {
        FreshDatabase db = FreshDatabase.create();

        assertThatThrownBy(() -> start(db, "classpath:seed/eur-account.json", "MXN,USD").close())
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e))
                        .isInstanceOf(SeedConfigurationException.class)
                        .hasMessageContaining("cbmm.currencies"));

        assertThat(output).contains("ACC_EUR").contains("cbmm.currencies");
        assertThat(JdbcClient.create(db.appDataSource()).sql("SELECT count(*) FROM account")
                .query(Integer.class).single()).isZero();
    }
}
