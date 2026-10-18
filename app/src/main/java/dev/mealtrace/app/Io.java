package dev.mealtrace.app;

import java.io.*;

final class Io {
  static byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > 2000000) throw new IOException("Asset too large");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }
}
