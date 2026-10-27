#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///

import io
import json
import logging
import os
import subprocess
import sys
import tempfile
import unittest
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
                "public final class Witness { public static int value() { return 17; } }\n"
            )
            classes = root / "classes"
            classes.mkdir()
            java_home = Path(os.environ["JAVA_HOME"])
            subprocess.run(
                [
                    str(java_home / "bin/javac"),
                    "--release",
                    "8",
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
            self.assertEqual(resource["bytes"], 3)
            self.assertEqual(
                resource["sha256"],
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            )
            artifact = release.path_identity(jar)
            self.assertEqual(artifact["kind"], "file")
            self.assertEqual(artifact["bytes"], jar.stat().st_size)
            (classes / "resource.bin").write_bytes(b"changed")
            changed = release.path_identity(classes)
            self.assertEqual(
                next(
                    item["bytes"]
                    for item in changed["files"]
                    if item["path"] == "resource.bin"
                ),
                7,
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
