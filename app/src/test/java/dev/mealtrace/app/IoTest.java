package dev.mealtrace.app;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class IoTest {
  @Test
  public void expandedCatalogLoadsWithinItsExplicitLimit() throws Exception {
    File asset = new File("src/main/assets/foods.json");
    assertTrue(asset.length() > 2000000);
    try (InputStream in = new FileInputStream(asset)) {
      assertEquals(asset.length(), Io.read(in, 8000000).length);
    }
  }

  @Test
  public void defaultLimitRemainsBounded() throws Exception {
    try {
      Io.read(new ByteArrayInputStream(new byte[2000001]));
      fail("The default reader should reject oversized content");
    } catch (IOException expected) {
      assertEquals("Asset too large", expected.getMessage());
    }
  }

  @Test
  public void exactLimitAcceptsContentAndRejectsOneExtraByte() throws Exception {
    assertEquals(8192, Io.read(new ByteArrayInputStream(new byte[8192]), 8192).length);
    try {
      Io.read(new ByteArrayInputStream(new byte[8193]), 8192);
      fail("The explicit limit should remain bounded");
    } catch (IOException expected) {
      assertEquals("Asset too large", expected.getMessage());
    }
  }
}
