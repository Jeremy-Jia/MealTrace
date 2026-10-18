package dev.mealtrace.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class InferenceFallbackTest {
  @Test public void unsupportedDeviceNeverStartsAccelerator() throws Exception {
    assertEquals("cpu", InferenceFallback.run(false, () -> { throw new AssertionError(); },
        () -> "cpu", new AtomicBoolean(), e -> { throw new AssertionError(); }));
  }

  @Test public void acceleratorFailureRetriesCpuExactlyOnce() throws Exception {
    AtomicInteger failed = new AtomicInteger(), cpu = new AtomicInteger();
    assertEquals("cpu", InferenceFallback.run(true, () -> { throw new IOException("driver unavailable"); },
        () -> { cpu.incrementAndGet(); return "cpu"; }, new AtomicBoolean(), e -> failed.incrementAndGet()));
    assertEquals(1, failed.get());
    assertEquals(1, cpu.get());
  }

  @Test public void successfulNpuDoesNotRunCpu() throws Exception {
    assertEquals("npu", InferenceFallback.run(true, () -> "npu", () -> { throw new AssertionError(); },
        new AtomicBoolean(), e -> { throw new AssertionError(); }));
  }

  @Test public void cancellationNeverRestartsAsCpu() throws Exception {
    AtomicBoolean stop = new AtomicBoolean();
    try {
      InferenceFallback.run(true, () -> { stop.set(true); throw new IOException(); },
          () -> { throw new AssertionError(); }, stop, e -> { throw new AssertionError(); });
      fail();
    } catch (InterruptedException expected) {}
  }

  @Test public void interruptedNpuNeverFallsBack() throws Exception {
    try {
      InferenceFallback.run(true, () -> { throw new InterruptedException(); },
          () -> { throw new AssertionError(); }, new AtomicBoolean(), e -> { throw new AssertionError(); });
      fail();
    } catch (InterruptedException expected) {}
  }

  @Test public void cpuFailureReachesCallerWithoutRetryLoop() throws Exception {
    IOException failure = new IOException("cpu failed");
    try {
      InferenceFallback.run(true, () -> { throw new IOException(); },
          () -> { throw failure; }, new AtomicBoolean(), e -> {});
      fail();
    } catch (IOException actual) { assertSame(failure, actual); }
  }

  @Test public void onlyCompatibleDspAndCpuCanTryNpu() {
    assertTrue(Acceleration.compatible(true, 85, true));
    assertTrue(Acceleration.compatible(true, 81, true));
    assertFalse(Acceleration.compatible(true, 79, true));
    assertFalse(Acceleration.compatible(true, 0, false));
    assertFalse(Acceleration.compatible(false, 85, true));
    assertFalse(Acceleration.compatible(true, 85, false));
  }
}
