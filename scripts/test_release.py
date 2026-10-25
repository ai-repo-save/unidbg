#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///

import os
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
