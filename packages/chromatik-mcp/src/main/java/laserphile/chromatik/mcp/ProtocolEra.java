package laserphile.chromatik.mcp;

/**
 * Which shape of the Model Context Protocol a request is speaking.
 *
 * <p>The protocol was reorganised on 2026-07-28 in a way that is not backwards compatible. Before
 * that revision a client opened with an {@code initialize} handshake and the agreed version lived
 * in the connection; from that revision on there is no handshake at all and every request carries
 * its own version, identity and capabilities as metadata. The specification calls these the legacy
 * and modern eras, and its compatibility matrix is blunt about mixing them: a modern client against
 * a legacy-only server fails outright, and so does the reverse.
 *
 * <p>A server that answers both works with every client either way round, which is why this server
 * does. The cost is small and contained: era selection is one header check in {@link McpDispatcher},
 * and the only shape difference in a result is handled in {@link ModernEnvelope}.
 */
enum ProtocolEra {

  /** Handshake-based. {@code initialize} opens the conversation. */
  LEGACY("2025-11-25"),

  /** Stateless. Every request carries its own version; {@code server/discover} replaces the handshake. */
  MODERN("2026-07-28");

  final String version;

  ProtocolEra(String version) {
    this.version = version;
  }

  /**
   * The era a request is speaking, or null if it names a version this server does not implement.
   *
   * <p>A missing header is treated as legacy. The header only became mandatory in 2025-06-18, so
   * an older client will not send one, and the specification allows a server to read its absence
   * as an early version rather than rejecting it.
   */
  static ProtocolEra forVersion(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return LEGACY;
    }

    if (MODERN.version.equals(headerValue)) {
      return MODERN;
    }

    // Every handshake-based revision is answered the same way, so they all map to LEGACY.
    return switch (headerValue) {
      case "2025-11-25", "2025-06-18", "2025-03-26" -> LEGACY;
      default -> null;
    };
  }

  /** The versions this server implements, newest first, for {@code server/discover} and errors. */
  static String[] supportedVersions() {
    return new String[] { MODERN.version, LEGACY.version };
  }
}
