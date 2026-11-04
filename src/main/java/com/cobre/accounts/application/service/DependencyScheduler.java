package com.cobre.accounts.application.service;

import com.cobre.accounts.domain.model.AccountId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

/**
 * Deterministic concurrency of D5 (spec §7.4). Jobs come in D4 order. A job depends on the last
 * earlier job that shares each of its CLIENT accounts (at most two), and becomes ready only when
 * all of them finished. Ready jobs start in D4 order, at most {@code maxConcurrency} at a time, each
 * on a virtual thread. Only ready jobs hold a slot, so a chain on one account never starves the
 * limit, and no more than {@code maxConcurrency} threads exist at once.
 *
 * <p>The first exception stops the load: no further job starts, a job whose dependency failed or
 * never ran does not run, the jobs in flight are awaited, and then that exception is rethrown with
 * any later ones suppressed into it.
 */
public final class DependencyScheduler {

    /**
     * @param keys the CLIENT accounts of the event; jobs sharing one run in list order
     */
    public record Job<T>(Set<AccountId> keys, Supplier<T> work) {

        public Job {
            keys = Set.copyOf(keys);
            Objects.requireNonNull(work, "work");
        }
    }

    private record Completion(int index, Object result, Throwable failure) {}

    private final int maxConcurrency;

    public DependencyScheduler(int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("max-concurrency must be at least 1: " + maxConcurrency);
        }
        this.maxConcurrency = maxConcurrency;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    /** @return the result of each job, in job order */
    @SuppressWarnings("unchecked")
    public <T> List<T> run(List<Job<T>> jobs) {
        int n = jobs.size();
        int[] pending = new int[n];
        List<List<Integer>> dependents = new ArrayList<>(n);
        Map<AccountId, Integer> lastByAccount = new HashMap<>();
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < n; i++) {
            dependents.add(new ArrayList<>(2));
            // Set: a job depending twice on the same earlier job counts it once.
            Set<Integer> dependencies = new HashSet<>(2);
            for (AccountId key : jobs.get(i).keys()) {
                Integer previous = lastByAccount.put(key, i);
                if (previous != null) {
                    dependencies.add(previous);
                }
            }
            for (int dependency : dependencies) {
                dependents.get(dependency).add(i);
            }
            pending[i] = dependencies.size();
            if (pending[i] == 0) {
                ready.add(i);
            }
        }

        Object[] results = new Object[n];
        BlockingQueue<Completion> completions = new LinkedBlockingQueue<>();
        Throwable failure = null;
        int running = 0;
        int finished = 0;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                while (failure == null && running < maxConcurrency && !ready.isEmpty()) {
                    int index = ready.poll();
                    Supplier<T> work = jobs.get(index).work();
                    threads.execute(() -> {
                        Completion completion;
                        try {
                            completion = new Completion(index, work.get(), null);
                        } catch (Throwable t) {
                            completion = new Completion(index, null, t);
                        }
                        completions.add(completion);
                    });
                    running++;
                }
                if (running == 0) {
                    break;
                }
                Completion completion = take(completions);
                running--;
                if (completion.failure() != null) {
                    if (failure == null) {
                        failure = completion.failure();
                    } else if (completion.failure() != failure) {
                        // The same instance from two jobs cannot be suppressed into itself.
                        failure.addSuppressed(completion.failure());
                    }
                    continue;
                }
                finished++;
                results[completion.index()] = completion.result();
                for (int dependent : dependents.get(completion.index())) {
                    if (--pending[dependent] == 0) {
                        ready.add(dependent);
                    }
                }
            }
        }
        if (failure != null) {
            throw rethrow(failure);
        }
        if (finished != n) {
            throw new IllegalStateException("Scheduler finished " + finished + " of " + n + " jobs");
        }
        List<T> ordered = new ArrayList<>(n);
        for (Object result : results) {
            ordered.add((T) result);
        }
        return ordered;
    }

    private static Completion take(BlockingQueue<Completion> completions) {
        try {
            return completions.take();
        } catch (InterruptedException e) {
            // With the interrupt flag set, closing the executor interrupts the jobs in flight
            // (shutdownNow). Only the transactions that have not committed yet roll back, and their
            // events stay undecided; a job that already committed stays decided, and sending the
            // files again reports it as a duplicate (D6).
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for events of the load", e);
        }
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(failure);
    }
}
