#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///

import argparse
import hashlib
import json
import logging
import os
import signal
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TESTS = ("JniProxyReflectionAccessIntegrationTest", "JniProxyExceptionIntegrationTest")
LOGGER = logging.getLogger("unidbg.release")


class JsonFormatter(logging.Formatter):
    def __init__(self, run_id):
        super().__init__()
        self.run_id = run_id

    def format(self, record):
        event = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "event": record.getMessage(),
            "run_id": self.run_id,
        }
        event.update(getattr(record, "context", {}))
        if record.exc_info:
            event["exception"] = self.formatException(record.exc_info)
        return json.dumps(event)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def require_clean_source():
    if git("status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Release requires a committed, clean source tree")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command, env, output, *, capture=False):
    LOGGER.info("command.started", extra={"context": {"command": command}})
    captured = []
    with subprocess.Popen(
        command,
        cwd=ROOT,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    ) as process:
        try:
            for line in process.stdout:
                sys.stderr.write(line)
                output.write(line)
                output.flush()
                if capture:
                    captured.append(line)
            result = process.wait()
        except BaseException:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait()
            raise
    captured_output = "".join(captured)
    if result:
        LOGGER.error(
            "command.failed",
            extra={
                "context": {
                    "command": command,
                    "returncode": result,
                    "output": captured_output,
                }
            },
        )
        raise subprocess.CalledProcessError(result, command, output=captured_output)
    LOGGER.info("command.completed", extra={"context": {"command": command}})
    return captured_output


def reactor_modules():
    pom = ET.parse(ROOT / "pom.xml").getroot()
    return [ROOT / module.text for module in pom.findall("m:modules/m:module", NS)]


def clean_command(maven):
    projects = ",".join(str(module.relative_to(ROOT)) for module in reactor_modules())
    return maven + ["-pl", projects, "clean"]


def reactor():
    pom = ET.parse(ROOT / "pom.xml").getroot()
    group = pom.findtext("m:groupId", namespaces=NS)
    version = pom.findtext("m:version", namespaces=NS)
    artifacts = []
    for module in [ROOT, *reactor_modules()]:
        model = ET.parse(module / "pom.xml").getroot()
        artifact = model.findtext("m:artifactId", namespaces=NS)
        packaging = model.findtext("m:packaging", default="jar", namespaces=NS)
        extensions = [".pom"]
        if packaging == "jar":
            extensions += [".jar", "-sources.jar", "-javadoc.jar"]
        artifacts.append((artifact, extensions))
    return group, version, artifacts


def release(args, run_id, raw_log):
    require_clean_source()
    baseline = git("rev-parse", "--verify", args.baseline + "^{commit}")
    subprocess.run(
        ["git", "merge-base", "--is-ancestor", baseline, "HEAD"],
        cwd=ROOT,
        check=True,
    )
    source_commit = git("rev-parse", "HEAD")
    source = {
        "fork": "https://github.com/ai-repo-save/unidbg",
        "upstream": "https://github.com/zhkl0228/unidbg",
        "baseline_commit": baseline,
        "release_commit": source_commit,
        "release_tree": git("rev-parse", "HEAD^{tree}"),
        "commit_url": f"https://github.com/ai-repo-save/unidbg/tree/{source_commit}",
    }
    repository = args.repository.resolve()
    if not repository.is_relative_to(ROOT / "target") or repository == ROOT / "target":
        raise ValueError("Release repository must be a subdirectory of project target/")
    if repository.exists():
        raise ValueError(
            "Release repository already exists; archive it before rebuilding"
        )
    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.java_home.resolve())
    env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    java = run(
        [
            str(args.java_home.resolve() / "bin/java"),
            "-XshowSettings:properties",
            "-version",
        ],
        env,
        raw_log,
        capture=True,
    )
    if "java.specification.version = 21\n" not in java:
        raise ValueError("Release requires JDK 21")
    local_repository = args.maven_local_repository.resolve()
    maven = [str(ROOT / "mvnw"), "-B", f"-Dmaven.repo.local={local_repository}"]
    LOGGER.info(
        "release.started",
        extra={
            "context": {"source": source, "repository": str(repository), "java": java}
        },
    )
    maven_version = run(maven + ["--version"], env, raw_log, capture=True)
    commands = [clean_command(maven)]
    run(commands[-1], env, raw_log)
    reports = []
    for test in TESTS:
        command = maven + [
            "-pl",
            "unidbg-android",
            "-am",
            "-Dmaven.test.skip=false",
            f"-Dtest={test}",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "test",
        ]
        commands.append(command)
        run(command, env, raw_log)
        report = (
            ROOT
            / "unidbg-android/target/surefire-reports"
            / (f"TEST-com.github.unidbg.linux.android.dvm.{test}.xml")
        )
        result = ET.parse(report).getroot()
        counts = {
            name: int(result.get(name))
            for name in ("tests", "failures", "errors", "skipped")
        }
        if counts != {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}:
            raise ValueError(
                f"Integration test did not pass completely: {test}: {counts}"
            )
        reports.append({"class": test, "counts": counts, "sha256": sha256(report)})
    command = maven + [
        "-Dgpg.skip=true",
        "install",
        "org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy",
        f"-DaltDeploymentRepository=local-release::{repository.as_uri()}",
    ]
    commands.append(command)
    run(command, env, raw_log)
    require_clean_source()
    group, version, artifacts = reactor()
    gavs = []
    installed = []
    for artifact, extensions in artifacts:
        gavs.append(f"{group}:{artifact}:{version}")
        for extension in extensions:
            relative = (
                Path(group.replace(".", "/"))
                / artifact
                / version
                / (f"{artifact}-{version}{extension}")
            )
            digest = sha256(repository / relative)
            if sha256(local_repository / relative) != digest:
                raise ValueError(f"Installed artifact differs from release: {relative}")
            installed.append({"path": relative.as_posix(), "sha256": digest})
    (repository / "LICENSE").write_bytes((ROOT / "LICENSE").read_bytes())
    files = [
        {"path": path.relative_to(repository).as_posix(), "sha256": sha256(path)}
        for path in sorted(repository.rglob("*"))
        if path.is_file()
    ]
    manifest = {
        "run_id": run_id,
        "source": source,
        "java": java,
        "maven": maven_version,
        "commands": commands,
        "tests": reports,
        "coordinates": gavs,
        "maven_local_repository": str(local_repository),
        "installed_artifacts": installed,
        "files": files,
    }
    manifest_path = repository / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    LOGGER.info(
        "release.completed",
        extra={
            "context": {"manifest": str(manifest_path), "sha256": sha256(manifest_path)}
        },
    )
    print(
        json.dumps(
            {
                "repository": str(repository),
                "manifest": str(manifest_path),
                "coordinates": gavs,
            }
        )
    )


def main():
    parser = argparse.ArgumentParser(
        description="Build, verify, install and release the committed unidbg fork locally."
    )
    parser.add_argument(
        "--baseline",
        required=True,
        help="Baseline Git commit; must be an ancestor of HEAD",
    )
    parser.add_argument(
        "--java-home", type=Path, required=True, help="JDK 21 installation directory"
    )
    parser.add_argument(
        "--repository",
        type=Path,
        default=ROOT / "target/release-0.10.0",
        help="New Maven-layout repository under project target/",
    )
    parser.add_argument(
        "--maven-local-repository",
        type=Path,
        default=Path.home() / ".m2/repository",
        help="Maven cache/install repository (default: ~/.m2/repository)",
    )
    args = parser.parse_args()
    run_id = uuid.uuid4().hex
    logs = ROOT / "logs"
    logs.mkdir(exist_ok=True)
    formatter = JsonFormatter(run_id)
    LOGGER.setLevel(logging.INFO)
    for handler in (
        logging.StreamHandler(),
        logging.FileHandler(logs / f"release-{run_id}.jsonl"),
    ):
        handler.setFormatter(formatter)
        LOGGER.addHandler(handler)
    try:
        with (logs / f"release-{run_id}.maven.log").open("w") as raw_log:
            release(args, run_id, raw_log)
    except KeyboardInterrupt:
        LOGGER.exception("release.cancelled")
        return 130
    except Exception:
        LOGGER.exception("release.failed")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
