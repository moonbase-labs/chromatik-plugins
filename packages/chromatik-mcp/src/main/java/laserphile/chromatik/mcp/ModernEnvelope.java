package laserphile.chromatik.mcp;

import com.google.gson.JsonObject;

/**
 * The fields a result carries in the modern era and not in the legacy one.
 *
 * <p>Everything the 2026-07-28 revision added to the shape of a result is applied here, so the
 * tools and the dispatcher can build one result and let the era decide how it is dressed. Keeping
 * it in a single class is deliberate: these field names come from a revision published days before
 * this was written, and a correction should be one file rather than a search.
 */
final class ModernEnvelope {

  /** A finished result. The alternative, {@code input_required}, belongs to multi round-trip
      requests, which this server does not use: no tool here needs to ask the client anything. */
  private static final String COMPLETE = "complete";

  /**
   * How long a client may treat a cacheable result as fresh.
   *
   * <p>Five minutes. The tool list only changes when a package is installed, which needs a restart,
   * so this could be far longer; it is kept short because {@code lx_catalog} and the tool
   * descriptions do reflect what is installed, and a stale answer after the user adds a package is
   * more annoying than the occasional refetch.
   */
  private static final long CACHE_TTL_MS = 300_000;

  /** Nothing this server returns is user-specific, so any cache may share it. */
  private static final String CACHE_SCOPE_PUBLIC = "public";

  /**
   * Mark a result as complete. Applied to every modern result, and to no legacy one.
   *
   * <p>The presence of {@code resultType} is what tells a dual-era client which era answered, so
   * putting it on a legacy result would be actively misleading rather than merely redundant.
   */
  static JsonObject complete(JsonObject result) {
    result.addProperty("resultType", COMPLETE);

    return result;
  }

  /**
   * Add the caching hints. The specification says servers MUST include these on a complete result
   * from {@code server/discover}, {@code tools/list} and the other list-shaped operations.
   */
  static JsonObject cacheable(JsonObject result) {
    result.addProperty("ttlMs", CACHE_TTL_MS);
    result.addProperty("cacheScope", CACHE_SCOPE_PUBLIC);

    return result;
  }

  private ModernEnvelope() {
  }
}
