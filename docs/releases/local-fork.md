# Local fork release

## Source

- Fork SCM: `https://github.com/ai-repo-save/unidbg`.
- Upstream: `https://github.com/zhkl0228/unidbg`.
- Fork baseline: `129151171590d0cfaaf46f98f93f1ac5f97fc401`.
- License: Apache 2.0, retained in `LICENSE`.

The release uses committed, clean source descended from the baseline. The generated manifest records both the baseline and the release commit's Git tree ID. These identities are generated after the source commit, outside Git, so the versioned source contains no self-referencing release commit hash. No remote source or artifact publication is performed.

## Coordinates

All reactor artifacts use group `io.github.ai-repo-save.unidbg` and version `0.9.10`:

- `unidbg` (parent POM).
- `unidbg-api`.
- `unidbg-android`.
- `unidbg-ios`.
- `unidbg-dynarmic`.
- `unidbg-hypervisor`.
- `unidbg-kvm`.
- `unidbg-unicorn2`.

Each JAR module provides its main, sources and Javadoc JARs. Java packages, native JNI symbols and the baseline patches retain their existing identities. Third-party libraries, including `com.github.zhkl0228:{unicorn,capstone,keystone,demumble}`, retain their published coordinates.

## Release

Requirements: Git, uv, JDK 21 and the repository Maven Wrapper (Maven 3.9.9). Maven uses `~/.m2/settings.xml` for configured HTTP/HTTPS proxies; configure the local proxy there when network access requires it.

From the repository root:

```sh
uv run --python 3.12 scripts/release.py \
  --baseline 129151171590d0cfaaf46f98f93f1ac5f97fc401 \
  --java-home /usr/lib/jvm/java-21-openjdk-amd64
```

`--help` lists repository and Maven cache options. `--repository` must select a new directory below this project's `target/`. The entry point rejects existing release directories. To retain a published release, select a different new directory for the next release. Maven `clean` selects only the child modules listed in the parent POM; the parent project's `target/` retains all existing release artifacts and manifests.

The entry point performs these steps with JDK 21:

1. Verify clean committed source, baseline ancestry and toolchain.
2. Clean the compilation modules with `-pl <child-module-paths> clean`, excluding the parent POM project.
3. Run `JniProxyReflectionAccessIntegrationTest` and `JniProxyExceptionIntegrationTest` separately with `-pl unidbg-android -am -Dmaven.test.skip=false -Dtest=<single-class> -Dsurefire.failIfNoSpecifiedTests=false test`. Each report must contain exactly one passed test and zero skipped tests.
4. Run `install org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy -Dgpg.skip=true -DaltDeploymentRepository=local-release::file://<repository>`. The ordinary install and file-repository deployment use the same Maven session and attached artifacts. Existing Javadoc production remains enabled; local GPG signing is skipped. The Central publishing lifecycle is not invoked.
5. Compare all released POMs and JARs against their ordinary Maven installations using SHA-256. Write the release manifest only after all checks pass.

Default outputs:

- Maven-layout repository: `target/release-repository/`.
- Ordinary Maven installation/cache: `~/.m2/repository/`.
- Provenance: `target/release-repository/release-manifest.json`, with source identities, toolchain, exact commands, test results, stable GAVs and file SHA-256 digests.
- License: `target/release-repository/LICENSE`.
- Structured release events: `logs/release-<run-id>.jsonl`.
- Original Maven output: `logs/release-<run-id>.maven.log`.

stdout contains the final JSON result. Events and Maven diagnostics go to stderr. Failure returns exit code 1; cancellation returns 130 and terminates the active Maven process group. A failed attempt may leave partial Maven installations or file-repository artifacts; only a repository with the final manifest is a verified release. Retain the two per-run log files through release acceptance, then remove them when no longer needed for diagnosis. No automatic retention cleanup runs.

## Release entry point verification

Run the filesystem-preservation integration test explicitly with JDK 21. It creates a minimal Maven fixture under `target/`, runs only the fixture's clean command, and independently reads the retained published files. It performs no install or deployment.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
uv run --python 3.12 scripts/test_release.py \
  ReleaseIntegrationTest.test_clean_preserves_published_files
```

## Consume

Same-user Maven and Gradle builds can use the ordinary Maven installation. To consume the exported repository explicitly, configure its absolute file URL:

```xml
<repository>
    <id>unidbg-local-release</id>
    <url>file:///home/bluebird/git/unidbg/target/release-repository</url>
</repository>
```

For example, the Android runtime dependency is `io.github.ai-repo-save.unidbg:unidbg-android:0.9.10`. Transitive reactor dependencies resolve from the same repository. Third-party dependencies still resolve from their configured upstream repositories or Maven cache. The exported fork repository is not a complete offline dependency mirror.
