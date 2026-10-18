package dev.mealtrace.app;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class InferenceFallback {
  static void check(AtomicBoolean stop) throws InterruptedException {
    if (stop.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
  }

  static <T> T run(boolean useNpu, Callable<T> npu, Callable<T> cpu,
      AtomicBoolean stop, Consumer<Exception> failed) throws Exception {
    check(stop);
    if (useNpu) {
      try {
        T result = npu.call();
        check(stop);
        return result;
      } catch (InterruptedException cancelled) {
        throw cancelled;
      } catch (Exception error) {
        check(stop);
        failed.accept(error);
      }
    }
    check(stop);
    return cpu.call();
  }
}
