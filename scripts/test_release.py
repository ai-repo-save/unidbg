#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///

import hashlib
import io
import json
import logging
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

import release


class ReleaseIntegrationTest(unittest.TestCase):
    def test_clean_preserves_published_files(self):
        target = release.ROOT / "target"
        target.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="release-clean-test-", dir=target
        ) as directory:
            root = Path(directory)
            (root / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<modelVersion>4.0.0</modelVersion><groupId>release.fixture</groupId>"
                "<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>"
                "<modules><module>module</module></modules></project>"
            )
            module = root / "module"
            module.mkdir()
            (module / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<modelVersion>4.0.0</modelVersion><groupId>release.fixture</groupId>"
                "<artifactId>module</artifactId><version>1</version></project>"
            )
            (module / "target").mkdir()
            (module / "target/classes.marker").write_bytes(b"rebuildable classes")
            published = {
                "target/release-repository/release-manifest.json": b'{"release":"published"}\n',
                "target/release-repository/artifact.jar": b"published artifact fixture\n",
                "target/other-release/release-manifest.json": b'{"release":"other"}\n',
            }
            for relative, content in published.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            maven = [
                str(release.ROOT / "mvnw"),
                "-B",
                "-o",
                "-f",
                str(root / "pom.xml"),
            ]
            with (
                patch.object(release, "ROOT", root),
                (root / "clean.log").open("w") as output,
            ):
                release.run(release.clean_command(maven), os.environ.copy(), output)
            actual = {
                path.relative_to(root).as_posix(): path.read_bytes()
                for path in (root / "target").rglob("*")
                if path.is_file()
            }
            self.assertEqual(actual, published)

    def test_compiled_directory_and_jar_identity(self):
        target = release.ROOT / "target"
        target.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="compiled-identity-test-", dir=target
        ) as directory:
            root = Path(directory)
            source = root / "Witness.java"
            source.write_text(
                "public final class Witness { public static void main(String[] args) throws Exception { "
                'try (java.io.InputStream input = Witness.class.getResourceAsStream("/resource.bin")) { '
                "input.transferTo(System.out); } } }\n"
            )
            classes = root / "classes"
            classes.mkdir()
            if "JAVA_HOME" not in os.environ:
                raise ValueError(
                    "Compiled identity integration requires JAVA_HOME pointing to JDK 21"
                )
            java_home = Path(os.environ["JAVA_HOME"])
            subprocess.run(
                [
                    str(java_home / "bin/javac"),
                    "--release",
                    "21",
                    "-d",
                    str(classes),
                    str(source),
                ],
                check=True,
            )
            (classes / "resource.bin").write_bytes(b"abc")
            jar = root / "witness.jar"
            subprocess.run(
                [
                    str(java_home / "bin/jar"),
                    "--create",
                    "--file",
                    str(jar),
                    "-C",
                    str(classes),
                    ".",
                ],
                check=True,
            )
            identity = release.path_identity(classes)
            self.assertEqual(
                {item["path"] for item in identity["files"]},
                {"Witness.class", "resource.bin"},
            )
            resource = next(
                item for item in identity["files"] if item["path"] == "resource.bin"
            )
            observed = subprocess.check_output(
                [str(java_home / "bin/java"), "-cp", str(classes), "Witness"]
            )
            self.assertEqual(observed, (classes / "resource.bin").read_bytes())
            self.assertEqual(resource["sha256"], hashlib.sha256(observed).hexdigest())
            artifact = release.path_identity(jar)
            self.assertEqual(artifact["kind"], "file")
            self.assertEqual(artifact["bytes"], jar.stat().st_size)
            loaded = subprocess.check_output(
                [str(java_home / "bin/java"), "-cp", str(jar), "Witness"]
            )
            self.assertEqual(loaded, (classes / "resource.bin").read_bytes())

    def test_existing_gav_is_refused_without_file_changes(self):
        (release.ROOT / "target").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="release-gav-test-", dir=release.ROOT / "target"
        ) as directory:
            root = Path(directory)
            repository, cache = root / "repository", root / "cache"
            expected = {}
            for destination in (repository, cache):
                artifact = destination / "fixture/group/module/1/module-1.jar"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"published bytes")
                expected[artifact] = artifact.read_bytes()
            for destination in (repository, cache):
                with self.assertRaises(ValueError):
                    release.require_new_coordinates(
                        "fixture.group",
                        "1",
                        [("module", [".pom", ".jar"])],
                        (destination,),
                    )
            self.assertEqual({path: path.read_bytes() for path in expected}, expected)

    def test_native_jar_bytes_match_generated_resource(self):
        (release.ROOT / "target").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="release-native-test-", dir=release.ROOT / "target"
        ) as directory:
            root = Path(directory)
            generated = root / "libbridge.so"
            generated.write_bytes(b"generated native resource bytes")
            resource = "natives/linux_64/libbridge.so"
            jar = root / "module.jar"
            expected = [
                {
                    "artifact_id": "module",
                    "resource": resource,
                    "sha256": release.digest(generated),
                    "bytes": generated.stat().st_size,
                }
            ]
            with zipfile.ZipFile(jar, "w") as archive:
                archive.write(generated, resource)
            release.verify_packaged_native({"module": jar}, expected)
            with zipfile.ZipFile(jar) as archive:
                self.assertEqual(archive.read(resource), generated.read_bytes())
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr(resource, b"stale resource bytes")
            with self.assertRaises(ValueError):
                release.verify_packaged_native({"module": jar}, expected)

    def test_changed_source_persists_after_and_diff(self):
        (release.ROOT / "target").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="source-identity-test-", dir=release.ROOT / "target"
        ) as directory:
            root = Path(directory)
            source = root / "source"
            subprocess.run(
                [
                    "git",
                    "clone",
                    "--shared",
                    "--sparse",
                    str(release.ROOT),
                    str(source),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
            )
            subprocess.run(
                ["git", "-C", str(source), "sparse-checkout", "set", "scripts"],
                check=True,
            )
            output = root / "evidence"
            output.mkdir()
            with patch.object(release, "ROOT", source):
                before = release.source_identity()
                changed = source / "scripts/release.py"
                before_hash = hashlib.sha256(changed.read_bytes()).hexdigest()
                changed.write_bytes(
                    changed.read_bytes() + b"\n# source-change witness\n"
                )
                after_hash = hashlib.sha256(changed.read_bytes()).hexdigest()
                with self.assertRaises(ValueError):
                    release.verify_source_snapshot(before, output)
                after = json.loads((output / "source-after.json").read_text())
                self.assertEqual(after["files"]["scripts/release.py"], after_hash)
                self.assertEqual(
                    after["commit"],
                    subprocess.check_output(
                        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
                    ).strip(),
                )
                self.assertEqual(
                    after["tree"],
                    subprocess.check_output(
                        ["git", "-C", str(source), "rev-parse", "HEAD^{tree}"],
                        text=True,
                    ).strip(),
                )
                self.assertEqual(
                    after["status"],
                    subprocess.check_output(
                        [
                            "git",
                            "-C",
                            str(source),
                            "status",
                            "--porcelain",
                            "--untracked-files=all",
                        ],
                        text=True,
                    ).strip(),
                )
                differences = json.loads((output / "source-changes.json").read_text())
                self.assertEqual(
                    differences["files"],
                    {
                        "scripts/release.py": {
                            "before": before_hash,
                            "after": after_hash,
                        }
                    },
                )
                self.assertEqual(
                    (output / "source-after.patch").read_text(),
                    subprocess.check_output(
                        ["git", "-C", str(source), "diff", "--binary", "HEAD"],
                        text=True,
                    ),
                )

    def test_probe_failures_preserve_original_output(self):
        target = release.ROOT / "target"
        target.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="release-probe-test-", dir=target
        ) as directory:
            root = Path(directory)
            for probe in ("java", "maven"):
                with self.subTest(probe=probe):
                    expected = f"{probe} stdout fixture\n{probe} stderr fixture\n"
                    command = [
                        sys.executable,
                        "-c",
                        (
                            "import sys; "
                            f"print('{probe} stdout fixture', flush=True); "
                            f"print('{probe} stderr fixture', file=sys.stderr, flush=True); "
                            "sys.exit(23)"
                        ),
                    ]
                    events = root / f"{probe}.jsonl"
                    raw = root / f"{probe}.log"
                    handler = logging.FileHandler(events)
                    handler.setFormatter(release.JsonFormatter("probe-test"))
                    release.LOGGER.addHandler(handler)
                    try:
                        with (
                            redirect_stderr(io.StringIO()) as diagnostics,
                            raw.open("w") as output,
                            self.assertRaises(subprocess.CalledProcessError) as failure,
                        ):
                            release.run(
                                command, os.environ.copy(), output, capture=True
                            )
                    finally:
                        release.LOGGER.removeHandler(handler)
                        handler.close()
                    self.assertEqual(failure.exception.returncode, 23)
                    self.assertEqual(failure.exception.output, expected)
                    self.assertEqual(raw.read_text(), expected)
                    self.assertEqual(diagnostics.getvalue(), expected)
                    records = [
                        json.loads(line) for line in events.read_text().splitlines()
                    ]
                    self.assertEqual(
                        [record["output"] for record in records if "output" in record],
                        [expected],
                    )


if __name__ == "__main__":
    unittest.main()
