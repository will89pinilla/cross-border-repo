package com.cobre.accounts.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.AccountsServiceApplication;
import com.cobre.accounts.application.port.in.LoadEventFilesUseCase;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.support.FreshDatabase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;

/**
 * The load through the real Spring wiring: {@code cbmm.processing.max-concurrency} reaches the
 * scheduler and must stay in {@code [1, maximum-pool-size)} (spec §7.4, §11): the load lock holds
 * one pool connection besides the events'.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoadWiringTest {

    private static ConfigurableApplicationContext start(String... extraArgs) {
        FreshDatabase db = FreshDatabase.create();
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=accounts_app",
                "--spring.datasource.password=app_test_pw",
                "--spring.flyway.user=accounts_owner",
                "--spring.flyway.password=owner_test_pw",
                "--cbmm.seed.path=classpath:fixtures/F01-case-event/seed.json"));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(AccountsServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    @Test
    void maxConcurrencyIs16ByDefault() {
        try (ConfigurableApplicationContext context = start()) {
            LoadResult result = context.getBean(LoadEventFilesUseCase.class).load(List::of);

            assertThat(result.maxConcurrency()).isEqualTo(16);
            assertThat(result.events()).isEmpty();
        }
    }

    @Test
    void maxConcurrencyComesFromConfiguration() {
        try (ConfigurableApplicationContext context = start("--cbmm.processing.max-concurrency=4")) {
            assertThat(context.getBean(LoadEventFilesUseCase.class).load(List::of).maxConcurrency()).isEqualTo(4);
        }
    }

    @Test
    void maxConcurrencyEqualToThePoolSizeStopsTheService(CapturedOutput output) {
        assertThatThrownBy(() -> start("--cbmm.processing.max-concurrency=20",
                        "--spring.datasource.hikari.maximum-pool-size=20").close())
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cbmm.processing.max-concurrency")
                        .hasMessageContaining("maximum-pool-size"));
        assertThat(output).contains("cbmm.processing.max-concurrency");
    }

    @Test
    void maxConcurrencyBelowOneStopsTheService() {
        assertThatThrownBy(() -> start("--cbmm.processing.max-concurrency=0").close())
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cbmm.processing.max-concurrency"));
    }

    @Test
    void maxConcurrencyJustBelowThePoolSizeIsAccepted() {
        try (ConfigurableApplicationContext context = start("--cbmm.processing.max-concurrency=5",
                "--spring.datasource.hikari.maximum-pool-size=6")) {
            assertThat(context.getBean(LoadEventFilesUseCase.class).load(List::of).maxConcurrency()).isEqualTo(5);
        }
    }
}
