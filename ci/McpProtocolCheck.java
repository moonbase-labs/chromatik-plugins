import java.io.File;
import java.util.jar.JarFile;
import laserphile.chromatik.mcp.McpSelfCheck;

/**
 * Release gate for the MCP package. The jar goes on the classpath and is also named as an argument
 * so its shape can be inspected:
 *
 * <pre>
 *   mvn -q -pl :chromatik-mcp dependency:build-classpath \
 *       -Dmdep.outputFile=target/provided.txt -Dmdep.includeScope=provided
 *
 *   JAR=packages/chromatik-mcp/target/chromatik-mcp-0.1.0-SNAPSHOT.jar
 *   java -cp "$JAR:$(cat packages/chromatik-mcp/target/provided.txt)" \
 *        ci/McpProtocolCheck.java "$JAR"
 * </pre>
 *
 * The provided dependencies have to be supplied, because the jar deliberately contains none of
 * them. That is the same arrangement Chromatik makes at runtime, so needing the extra step here is
 * the gate working rather than an inconvenience.
 *
 * Separate from {@link NativeLoadCheck} rather than folded into it. That gate requires every jar to
 * contain a pattern class, because it exists to prove a bundled FFmpeg native can be extracted and
 * used on the machine it shipped to. This package ships no pattern and no native, so handing it to
 * that check would fail the release for entirely the wrong reason.
 *
 * What it does guard is different and just as easy to get wrong silently:
 *
 * <ul>
 *   <li>the jar carries nothing but its own classes. gson is <em>provided</em> because Chromatik
 *       already has it; a scope slip would inline 213 classes into the shared class loader that
 *       every other installed package also lives in, and nothing else would notice;
 *   <li>the wire format still matches both protocol eras. {@code McpSelfCheck} drives the whole
 *       matrix with no socket and no Chromatik, so a change that breaks a client fails the build
 *       instead of a conversation.
 * </ul>
 *
 * Compiling against the jar is itself part of the gate, the same property that caught the core jar
 * being over-trimmed.
 *
 * Exits non-zero on the first failure.
 */
public class McpProtocolCheck {

  /**
   * The jar has no business being larger than this.
   *
   * <p>It holds roughly twenty small classes and one resource. The number is a tripwire for a
   * dependency arriving at compile scope, which is otherwise completely silent: the plugin would
   * still build, still load and still work, while quietly pushing someone else's classes into the
   * loader Chromatik shares between packages.
   */
  private static final long MAX_JAR_BYTES = 128 * 1024;

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      fail("usage: McpProtocolCheck <path to chromatik-mcp jar>");
    }

    checkJarShape(new File(args[0]));
    checkProtocol();

    System.out.println("OK: chromatik-mcp jar and protocol both pass");
  }

  private static void checkJarShape(File jarFile) throws Exception {
    if (!jarFile.isFile()) {
      fail("no such jar: " + jarFile);
    }

    final long size = jarFile.length();
    System.out.println("jar: " + jarFile.getName() + " (" + size + " bytes)");

    if (size > MAX_JAR_BYTES) {
      fail("jar is " + size + " bytes, over the " + MAX_JAR_BYTES
          + " byte limit. A dependency has probably arrived at compile scope instead of provided.");
    }

    try (JarFile jar = new JarFile(jarFile)) {
      requireEntry(jar, "lx.package");
      requireEntry(jar, "laserphile/chromatik/mcp/ChromatikMcpPlugin.class");

      // Nothing bundled. Named explicitly rather than left to the size check so the failure says
      // which dependency leaked in.
      refuseEntriesUnder(jar, "com/google/gson/");
      refuseEntriesUnder(jar, "org/bytedeco/");
      refuseEntriesUnder(jar, "heronarts/");
    }

    System.out.println("  jar shape ok: plugin present, nothing bundled");
  }

  private static void checkProtocol() {
    System.out.println("protocol self-check:");
    final int failures = McpSelfCheck.run();

    if (failures > 0) {
      fail(failures + " protocol check(s) failed");
    }
  }

  private static void requireEntry(JarFile jar, String name) {
    if (jar.getEntry(name) == null) {
      fail("jar is missing " + name);
    }
  }

  private static void refuseEntriesUnder(JarFile jar, String prefix) {
    final long count = jar.stream().filter(entry -> entry.getName().startsWith(prefix)).count();

    if (count > 0) {
      fail("jar bundles " + count + " entries under " + prefix
          + ", which Chromatik already provides. Check the dependency scope.");
    }
  }

  private static void fail(String message) {
    System.err.println("FAIL: " + message);
    System.exit(1);
  }
}
