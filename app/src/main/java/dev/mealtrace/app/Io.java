package dev.mealtrace.app;

import java.io.*;

final class Io {
  static byte[] read(InputStream in) throws IOException {
    return read(in, 2000000);
  }

  static byte[] read(InputStream in, int limit) throws IOException {
    if (limit <= 0) throw new IllegalArgumentException("Invalid read limit");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (n > limit - out.size()) throw new IOException("Asset too large");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }
}
