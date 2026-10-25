# Releasing

This repo publishes from tags only. A release appears on GitHub when a semver tag `v*` is pushed.

## Next release plan

Recommended next tag: `v0.2.1`.

Why: current unreleased changes are patch-level behavior and UX updates in `chromatik-touch` (camera orientation controls, more resilient Windows camera enumeration, and a stable preview port preference with fallback) without a package-surface break. That is a PATCH bump under the versioning policy below.

## Preflight checklist

1. Confirm docs match released assets:
   - `README.md` install table
   - `.github/release-notes.md` download table
2. Build all release variants locally:

```bash
mvn clean
mvn package -Pdist-macos
mvn package -Pdist-windows
mvn package -Pdist-linux-x86_64
mvn package -Pdist-linux-arm64
```

3. Optional local smoke checks:

```bash
java -cp packages/chromatik-core/target/chromatik-core-*-macos.jar \
  ci/NativeLoadCheck.java \
  packages/chromatik-video/target/chromatik-video-*.jar \
  packages/chromatik-screen/target/chromatik-screen-*.jar \
  packages/chromatik-shader/target/chromatik-shader-*-macos.jar
```

## Publish

```bash
git tag v0.2.1
git push origin v0.2.1
```

CI will:

1. Validate semver tag format
2. Stamp versions from the tag
3. Build all platform jars
4. Verify native loading on each target runner
5. Create the GitHub release with checksums and notes

## Post-release checks

1. Open the GitHub release and verify expected assets are present.
2. Open the rendered release notes and verify package names match assets.
3. Confirm README "latest release" link now resolves to the new tag.

## Versioning

Versions are [semver](https://semver.org/): `vMAJOR.MINOR.PATCH`, optionally with a `-prerelease` suffix. A tag that isn't fails the build before anything is published, and a `-prerelease` tag (`v0.2.1-rc.1`) is marked as such on GitHub so it stays out of "latest release". Build metadata (`+`) is rejected: semver ignores it for precedence and it mangles download URLs.

The tag is the only place a release version lives. The pom stays on `-SNAPSHOT` naming the release it's heading for, and CI overwrites it at build time so the jar reports a real version through `lx.package`.

What counts as breaking, for a Chromatik package, is compatibility with saved `.lxp` projects, since those store the pattern's class name and its parameter paths:

| | Means |
|---|---|
| **MAJOR** | A saved project won't reload cleanly: a parameter renamed or removed, the pattern class renamed, or `mediaDir` changed. |
| **MINOR** | New parameters, new patterns, a newly supported platform. Existing projects unaffected. |
| **PATCH** | Fixes and performance work with no change to the parameter surface. |

While MAJOR is `0` this is pre-1.0, so a MINOR bump is allowed to break things. Reaching 1.0 is the promise not to.
