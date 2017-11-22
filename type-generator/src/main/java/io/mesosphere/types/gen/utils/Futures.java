package io.mesosphere.types.gen.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Helper utilities for futures
 */
public class Futures {

    /**
     * Wait for all the futures in the given list to complete and return a future that will
     * contain a list of all the values.
     *
     * @param futures
     * @param <T>
     * @return
     */
    public static <T> CompletableFuture<List<T>> waitForAll(List<CompletableFuture<T>> futures) {
        CompletableFuture<List<T>> future = new CompletableFuture<>();
        final List<T> list = new ArrayList<>();

        futures.forEach(tCompletableFuture -> {
            tCompletableFuture.whenComplete((t, throwable) -> {
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                    return;
                }

                // Collect list
                list.add(t);

                // When full, complete the future
                if (list.size() == futures.size()) {
                    future.complete(list);
                }
            });
        });

        return future;
    }

}
