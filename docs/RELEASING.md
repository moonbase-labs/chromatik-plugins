# Releasing

This repo publishes from tags only. A release appears on GitHub when a semver tag `v*` is pushed.

## Next release plan

Recommended next tag: `v0.2.0`.

Why: the repo now documents and ships multiple packages beyond the original `v0.1.0` fat jar, including module splits and new patterns. That is a MINOR bump under the semver policy in the README.

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
git tag v0.2.0
git push origin v0.2.0
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
