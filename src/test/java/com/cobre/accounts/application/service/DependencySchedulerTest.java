package com.cobre.accounts.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.service.DependencyScheduler.Job;
import com.cobre.accounts.domain.model.AccountId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** D5 (spec §7.4): jobs sharing a CLIENT account run one at a time in D4 order; the others in parallel. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DependencySchedulerTest {

    private static final AccountId A = AccountId.of("ACC_A");
    private static final AccountId B = AccountId.of("ACC_B");
    private static final AccountId C = AccountId.of("ACC_C");

    @Test
    void resultsComeBackInJobOrder() {
        var jobs = IntStream.range(0, 30)
                .mapToObj(i -> new Job<>(Set.of(AccountId.of("ACC_" + (i % 3))), () -> i))
                .toList();

        assertThat(new DependencyScheduler(4).run(jobs)).containsExactlyElementsOf(IntStream.range(0, 30).boxed().toList());
    }

    @Test
    void emptyLoadRunsNothing() {
        assertThat(new DependencyScheduler(4).run(List.<Job<Integer>>of())).isEmpty();
    }

    @ParameterizedTest(name = "max-concurrency {0}")
    @ValueSource(ints = {1, 2})
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void chainOf50OnTheSameAccountDoesNotBlockAndKeepsOrder(int maxConcurrency) {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        var jobs = IntStream.range(0, 50).mapToObj(i -> new Job<>(Set.of(A), () -> {
            order.add(i);
            return i;
        })).toList();

        new DependencyScheduler(maxConcurrency).run(jobs);

        assertThat(order).containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
    }

    @Test
    void jobsSharingAnAccountNeverOverlapAndKeepD4Order() {
        List<AccountId> accounts = List.of(A, B, C);
        Map<AccountId, AtomicInteger> active = new ConcurrentHashMap<>();
        Map<AccountId, List<Integer>> orderByAccount = new ConcurrentHashMap<>();
        AtomicBoolean overlap = new AtomicBoolean();
        var jobs = new ArrayList<Job<Integer>>();
        for (int i = 0; i < 200; i++) {
            int index = i;
            // Pairs and singles over three accounts, many collisions.
            Set<AccountId> keys = i % 4 == 0 ? Set.of(accounts.get(i % 3)) : Set.of(accounts.get(i % 3), accounts.get((i + 1) % 3));
            jobs.add(new Job<>(keys, () -> {
                for (AccountId key : keys) {
                    if (active.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() > 1) {
                        overlap.set(true);
                    }
                    orderByAccount.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(index);
                }
                Thread.yield();
                keys.forEach(key -> active.get(key).decrementAndGet());
                return index;
            }));
        }

        new DependencyScheduler(16).run(jobs);

        assertThat(overlap).isFalse();
        Map<AccountId, List<Integer>> expected = new HashMap<>();
        for (int i = 0; i < jobs.size(); i++) {
            for (AccountId key : jobs.get(i).keys()) {
                expected.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        assertThat(orderByAccount).isEqualTo(expected);
    }

    @Test
    void disjointJobsRunInParallel() {
        // Each job waits until the other has started: only parallel execution finishes.
        CountDownLatch bothStarted = new CountDownLatch(2);
        var jobs = List.of(A, B).stream().map(key -> new Job<>(Set.of(key), () -> {
            bothStarted.countDown();
            return await(bothStarted);
        })).toList();

        assertThat(new DependencyScheduler(2).run(jobs)).containsExactly(true, true);
    }

    @Test
    void neverRunsMoreThanMaxConcurrencyAtOnce() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // The first wave holds until 4 jobs run together, so the peak is reached without timing.
        CountDownLatch fourRunning = new CountDownLatch(4);
        var jobs = IntStream.range(0, 64).mapToObj(i -> new Job<>(Set.of(AccountId.of("ACC_" + i)), () -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            fourRunning.countDown();
            await(fourRunning);
            running.decrementAndGet();
            return i;
        })).toList();

        new DependencyScheduler(4).run(jobs);

        assertThat(peak.get()).isEqualTo(4);
    }

    @Test
    void readyJobsStartInD4Order() {
        List<Integer> started = Collections.synchronizedList(new ArrayList<>());
        var jobs = IntStream.range(0, 40).mapToObj(i -> new Job<>(Set.of(AccountId.of("ACC_" + i)), () -> {
            started.add(i);
            return i;
        })).toList();

        new DependencyScheduler(1).run(jobs);

        assertThat(started).containsExactlyElementsOf(IntStream.range(0, 40).boxed().toList());
    }

    @Test
    void jobWithTwoDependenciesWaitsForBoth() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstDone = new AtomicBoolean();
        AtomicBoolean secondDone = new AtomicBoolean();
        var jobs = List.of(
                new Job<>(Set.of(A), () -> {
                    await(release);
                    firstDone.set(true);
                    return "a";
                }),
                new Job<>(Set.of(B), () -> {
                    secondDone.set(true);
                    release.countDown();
                    return "b";
                }),
                new Job<>(Set.of(A, B), () -> firstDone.get() && secondDone.get() ? "ab" : "too early"));

        assertThat(new DependencyScheduler(4).run(jobs)).containsExactly("a", "b", "ab");
    }

    @Test
    void jobsWithoutAccountsHaveNoDependencies() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        var jobs = IntStream.range(0, 2).mapToObj(i -> new Job<>(Set.<AccountId>of(), () -> {
            bothStarted.countDown();
            return await(bothStarted);
        })).toList();

        assertThat(new DependencyScheduler(2).run(jobs)).containsExactly(true, true);
    }

    @ParameterizedTest(name = "max-concurrency {0}")
    @ValueSource(ints = {1, 4})
    void anExceptionStopsTheLoadAndIsRethrown(int maxConcurrency) {
        int failing = 10;
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        IllegalStateException boom = new IllegalStateException("boom");
        // Job 11 depends on job 10 (same account); jobs 0..9 and 12.. are on their own accounts.
        var jobs = IntStream.range(0, 40).mapToObj(i -> new Job<>(
                Set.of(i == failing + 1 ? AccountId.of("ACC_" + failing) : AccountId.of("ACC_" + i)), () -> {
                    if (i == failing) {
                        throw boom;
                    }
                    ran.add(i);
                    return i;
                })).toList();

        assertThatThrownBy(() -> new DependencyScheduler(maxConcurrency).run(jobs)).isSameAs(boom);

        assertThat(ran).doesNotContain(failing + 1);
        assertThat(ran).containsAll(IntStream.range(0, failing).boxed().toList());
        if (maxConcurrency == 1) {
            assertThat(ran).containsExactlyElementsOf(IntStream.range(0, failing).boxed().toList());
        } else {
            // Besides jobs 0..9: up to max-1 in flight when job 10 failed, plus up to max-1 launched
            // from completions that were queued before the failure. Never the whole load.
            assertThat(ran).hasSizeLessThanOrEqualTo(failing + 2 * (maxConcurrency - 1));
        }
    }

    @Test
    void jobsInFlightFinishBeforeTheExceptionIsRethrown() {
        // The slow job is still running when the other one fails: it may only finish after the failure.
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch failing = new CountDownLatch(1);
        AtomicBoolean slowFinished = new AtomicBoolean();
        var jobs = List.of(
                new Job<>(Set.of(A), () -> {
                    slowStarted.countDown();
                    await(failing);
                    slowFinished.set(true);
                    return 0;
                }),
                new Job<Integer>(Set.of(B), () -> {
                    await(slowStarted);
                    failing.countDown();
                    throw new IllegalStateException("boom");
                }));

        assertThatThrownBy(() -> new DependencyScheduler(2).run(jobs)).hasMessage("boom");
        assertThat(slowFinished).isTrue();
    }

    @Test
    void laterFailuresAreSuppressedIntoTheFirst() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        var jobs = IntStream.range(0, 2).mapToObj(i -> new Job<Integer>(Set.of(AccountId.of("ACC_" + i)), () -> {
            bothStarted.countDown();
            await(bothStarted);
            throw new IllegalStateException("boom " + i);
        })).toList();

        // Which one is seen first depends on the threads; the other one is never lost.
        assertThatThrownBy(() -> new DependencyScheduler(2).run(jobs)).satisfies(e -> {
            List<String> all = new ArrayList<>(List.of(e.getMessage()));
            for (Throwable suppressed : e.getSuppressed()) {
                all.add(suppressed.getMessage());
            }
            assertThat(all).containsExactlyInAnyOrder("boom 0", "boom 1");
        });
    }

    /** The same instance from two jobs cannot be suppressed into itself: that would hide it. */
    @Test
    void theSameExceptionFromTwoJobsIsRethrownAsIs() {
        IllegalStateException shared = new IllegalStateException("shared boom");
        CountDownLatch bothStarted = new CountDownLatch(2);
        var jobs = IntStream.range(0, 2).mapToObj(i -> new Job<Integer>(Set.of(AccountId.of("ACC_" + i)), () -> {
            bothStarted.countDown();
            await(bothStarted);
            throw shared;
        })).toList();

        assertThatThrownBy(() -> new DependencyScheduler(2).run(jobs)).isSameAs(shared)
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
    }

    @Test
    void maxConcurrencyMustBeAtLeastOne() {
        assertThatThrownBy(() -> new DependencyScheduler(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Fails the job, and so the test, if the latch is not released: a timeout is never ignored. */
    private static boolean await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch released within 5 s").isTrue();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
