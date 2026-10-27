#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///

import argparse
import fcntl
import hashlib
import json
import logging
import os
import platform
import shutil
import signal
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TESTS = (
    "JniProxyReflectionAccessIntegrationTest",
    "JniProxyExceptionIntegrationTest",
    "JniOnLoadResultIntegrationTest",
    "JniProxyUnpackIntegrationTest",
    "JniPrimitiveIntegrationTest",
)
LOGGER = logging.getLogger("unidbg.release")
COMPONENTS = {
    "unidbg-api": "unidbg-api",
    "unidbg-android": "unidbg-android",
    "unidbg-unicorn2": "backend/unicorn2",
}
UNICORN_COMMIT = "8028ec436f2d9376525352dd38ed9ed6b9f6be10"


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


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def file_identity(path):
    path = Path(path)
    return {"path": str(path), "sha256": digest(path), "bytes": path.stat().st_size}


def path_identity(path):
    path = Path(path)
    if not path.is_dir():
        return {"kind": "file", **file_identity(path)}
    files = []
    for child in sorted(path.rglob("*")):
        if child.is_file():
            item = file_identity(child)
            item["path"] = child.relative_to(path).as_posix()
            files.append(item)
    return {"kind": "directory", "path": str(path), "files": files}


def source_identity():
    files = git("ls-files", "--cached", "--others", "--exclude-standard").splitlines()
    return {
        "commit": git("rev-parse", "HEAD"),
        "tree": git("rev-parse", "HEAD^{tree}"),
        "status": git("status", "--porcelain", "--untracked-files=all"),
        "files": {
            name: digest(ROOT / name) for name in files if (ROOT / name).is_file()
        },
    }


def native_build(args, env, output, commands):
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("Native preparation is defined for Linux x86_64")
    upstream = args.unicorn_source.resolve()
    commit = subprocess.check_output(
        ["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True
    ).strip()
    status = subprocess.check_output(
        ["git", "-C", str(upstream), "status", "--porcelain"], text=True
    ).strip()
    if commit != UNICORN_COMMIT or status:
        raise ValueError("Unicorn source must be the unchanged declared 2.1.4 commit")
    engine = ROOT / "target/unicorn-build"
    glue = ROOT / "target/unicorn-jni-build"
    monitor = ROOT / "unidbg-android/target/native-monitor"
    monitor_resources = (
        ROOT / "unidbg-android/target/generated-resources/natives/linux_64"
    )
    commands.extend(
        [
            [
                "cmake",
                "-S",
                str(upstream),
                "-B",
                str(engine),
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DUNICORN_ARCH=arm;aarch64",
                "-DUNICORN_BUILD_TESTS=OFF",
            ],
            [
                "cmake",
                "--build",
                str(engine),
                "--target",
                "unicorn_archive",
                "--parallel",
                str(args.jobs),
            ],
            [
                "cmake",
                "-S",
                str(ROOT / "backend/unicorn2/src/main/native"),
                "-B",
                str(glue),
                f"-DUNICORN_HOME={upstream}",
                f"-DUNICORN_BUILD={engine}",
            ],
            ["cmake", "--build", str(glue), "--parallel", str(args.jobs)],
            [
                "cmake",
                "-S",
                str(ROOT / "unidbg-android/src/main/native/jvm-monitor"),
                "-B",
                str(monitor),
                "-DCMAKE_BUILD_TYPE=Release",
                f"-DCMAKE_LIBRARY_OUTPUT_DIRECTORY={monitor_resources}",
            ],
            [
                "cmake",
                "--build",
                str(monitor),
                "--target",
                "unidbg_jvm_monitor",
                "--parallel",
                str(args.jobs),
            ],
        ]
    )
    for command in commands[-6:]:
        run(command, env, output)
    destination = (
        ROOT
        / "backend/unicorn2/target/generated-resources/natives/linux_64/libunicorn.so"
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(glue / "libunicorn.so", destination)
    return {
        "commit": commit,
        "source": str(upstream),
        "static_library": file_identity(engine / "libunicorn.a"),
    }


def prepared_native_resources():
    resources = []
    for artifact, resource in (
        ("unidbg-unicorn2", "natives/linux_64/libunicorn.so"),
        ("unidbg-android", "natives/linux_64/libunidbg_jvm_monitor.so"),
    ):
        module = ROOT / COMPONENTS[artifact]
        generated = module / "target/generated-resources" / resource
        effective = module / "target/classes" / resource
        identity = file_identity(generated)
        if digest(effective) != identity["sha256"]:
            raise ValueError(
                f"Effective native resource differs from this build: {resource}"
            )
        resources.append(
            {
                "artifact_id": artifact,
                "resource": resource,
                "sha256": identity["sha256"],
                "bytes": identity["bytes"],
            }
        )
    return resources


def build_only(args, run_id, raw_log):
    if args.unicorn_source is None or args.consumer_classpath_file is None:
        raise ValueError(
            "--build-only requires --unicorn-source and --consumer-classpath-file"
        )
    output = (args.build_output or ROOT / "target/evaluation-build" / run_id).resolve()
    if not output.is_relative_to(ROOT / "target"):
        raise ValueError("Build output must be inside this checkout's target/")
    output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.java_home.resolve())
    env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    maven = [
        str(ROOT / "mvnw"),
        "-B",
        f"-Dmaven.repo.local={args.maven_local_repository.resolve()}",
    ]
    if args.offline:
        maven.append("-o")
    with (ROOT / "target/evaluation-build.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        source = source_identity()
        (output / "source-before.json").write_text(json.dumps(source, indent=2) + "\n")
        java = run(
            [str(args.java_home / "bin/java"), "-XshowSettings:properties", "-version"],
            env,
            raw_log,
            capture=True,
        )
        if "java.specification.version = 21\n" not in java:
            raise ValueError("Build preparation requires JDK 21")
        commands = [clean_command(maven)]
        run(commands[-1], env, raw_log)
        native = native_build(args, env, raw_log, commands)
        commands.append(
            maven
            + [
                "-pl",
                "unidbg-android,backend/unicorn2",
                "-am",
                "compile",
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                "-DincludeScope=runtime",
                "-DexcludeGroupIds=io.github.ai-repo-save.unidbg",
                "-Dmdep.outputFile=target/evaluation-runtime-classpath.txt",
            ]
        )
        run(commands[-1], env, raw_log)
        resources = prepared_native_resources()
        group, version, _ = reactor()
        components = []
        dependencies = []
        for artifact, module in COMPONENTS.items():
            classes = ROOT / module / "target/classes"
            snapshot = output / "components" / artifact
            shutil.copytree(classes, snapshot)
            components.append(
                {
                    "group_id": group,
                    "artifact_id": artifact,
                    "version": version,
                    "path": str(snapshot),
                }
            )
            text = (
                (ROOT / module / "target/evaluation-runtime-classpath.txt")
                .read_text()
                .strip()
            )
            dependencies.extend(text.split(os.pathsep) if text else [])
        consumer = args.consumer_classpath_file.resolve()
        dependencies.extend(consumer.read_text().strip().split(os.pathsep))
        cp = [item["path"] for item in components] + list(dict.fromkeys(dependencies))
        identities = [path_identity(path) for path in cp]
        if source_identity() != source:
            raise ValueError("Source changed during the exclusive compilation window")
        manifest = {
            "schema_version": 1,
            "kind": "build",
            "published": False,
            "version": version,
            "run_id": run_id,
            "source": source,
            "platform": {"system": platform.system(), "machine": platform.machine()},
            "java": java,
            "maven": run(maven + ["--version"], env, raw_log, capture=True),
            "commands": commands,
            "components": components,
            "classpath": cp,
            "classpath_identity": identities,
            "native_resources": resources,
            "consumer_classpath_file": file_identity(consumer),
            "unicorn": native,
        }
        identity_file = output / "build-identity.json"
        identity_file.write_text(json.dumps(manifest, indent=2) + "\n")
        (output / "classpath.txt").write_text(os.pathsep.join(cp) + "\n")
        LOGGER.info(
            "build.prepared",
            extra={"context": {"identity": str(identity_file), "published": False}},
        )
        print(
            json.dumps(
                {
                    "identity_file": str(identity_file),
                    "classpath_file": str(output / "classpath.txt"),
                    "published": False,
                }
            )
        )


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
        reports.append(
            {
                "class": test,
                "counts": counts,
                "path": report.relative_to(ROOT).as_posix(),
            }
        )
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
    files = []
    for artifact, extensions in artifacts:
        gavs.append(f"{group}:{artifact}:{version}")
        for extension in extensions:
            relative = (
                Path(group.replace(".", "/"))
                / artifact
                / version
                / (f"{artifact}-{version}{extension}")
            )
            files.append(relative.as_posix())
    (repository / "LICENSE").write_bytes((ROOT / "LICENSE").read_bytes())
    files.append("LICENSE")
    manifest = {
        "run_id": run_id,
        "source": source,
        "java": java,
        "maven": maven_version,
        "commands": commands,
        "tests": reports,
        "coordinates": gavs,
        "maven_local_repository": str(local_repository),
        "files": files,
    }
    manifest_path = repository / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    LOGGER.info(
        "release.completed", extra={"context": {"manifest": str(manifest_path)}}
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
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--build-only",
        dest="build_only",
        action="store_true",
        default=True,
        help="Prepare an unpublished Linux x86_64 build without install/deploy (default)",
    )
    mode.add_argument(
        "--release",
        dest="build_only",
        action="store_false",
        help="Explicit formal release after publication approval",
    )
    parser.add_argument(
        "--build-output", type=Path, help="New build evidence directory under target/"
    )
    parser.add_argument(
        "--unicorn-source",
        type=Path,
        help="Unchanged Unicorn 2.1.4 Git source checkout",
    )
    parser.add_argument(
        "--consumer-classpath-file",
        type=Path,
        help="Explicit collector runtime dependencies (Jackson, picocli, logging); no core replacement",
    )
    parser.add_argument("--jobs", type=int, default=8)
    parser.add_argument(
        "--offline",
        action="store_true",
        help="Resolve Maven dependencies from the selected cache only",
    )
    parser.add_argument(
        "--baseline",
        help="Baseline Git commit; must be an ancestor of HEAD",
    )
    parser.add_argument(
        "--java-home", type=Path, required=True, help="JDK 21 installation directory"
    )
    parser.add_argument(
        "--repository",
        type=Path,
        default=ROOT / "target/release-0.13.2",
        help="Maven-layout repository under project target/ (created or reused)",
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
            if args.jobs < 1:
                raise ValueError("--jobs must be positive")
            if args.build_only:
                build_only(args, run_id, raw_log)
            else:
                if args.baseline is None:
                    raise ValueError("Formal release requires --baseline")
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
