"""Smoke test native MCP stdio support with the official Python SDK calculator.

From the repository root, prepare the fixture and build a runnable Specmatic JAR.

    git clone https://github.com/modelcontextprotocol/python-sdk.git temp/mcp-stdio-python-sdk
    git -C temp/mcp-stdio-python-sdk checkout 77001c96f9507be0f4d009b83210a384a5e08bda
    ./gradlew :specmatic-executable:unobfuscatedShadowJar

Run the smoke test with uv, using absolute paths for the script and JAR because
uv changes the working directory to the SDK checkout.

    uv run --locked --directory temp/mcp-stdio-python-sdk python \\
        "$PWD/application/src/test/smoke/mcp_stdio.py" \\
        "$PWD"/application/build/libs/*-all-unobfuscated.jar
"""

import json
from pathlib import Path
import subprocess
import sys
import tempfile


def run_specmatic(jar, work_dir, *args, **options):
    return subprocess.run(
        ["java", "-jar", str(jar), "mcp", "test", *args],
        cwd=work_dir,
        timeout=60,
        **options,
    )


def check_calculator_report(work_dir, run):
    if run.returncode != 0:
        raise SystemExit(f"Specmatic MCP Auto Test exited with status {run.returncode}")

    report = work_dir / "build/reports/specmatic/mcp/mcp_test_report.json"
    if not report.is_file():
        raise SystemExit(f"Specmatic did not write the MCP test report at {report}")

    scenarios = json.loads(report.read_text(encoding="utf-8"))
    tools = {scenario["toolName"] for scenario in scenarios}
    if tools != {"calc", "echo"}:
        raise SystemExit(f"Expected scenarios for calc and echo, got {sorted(tools)}")
    failures = [
        scenario["name"] for scenario in scenarios if scenario["verdict"] != "PASSED"
    ]
    if failures:
        raise SystemExit(f"MCP scenarios failed: {', '.join(failures)}")

    print(f"Passed {len(scenarios)} MCP scenarios over stdio for calc and echo.")


def check_config(jar, work_dir, version):
    server_dir = work_dir / "server files"
    server_dir.mkdir()
    (server_dir / "launch calculator.py").write_text(
        """import os
from pathlib import Path
import runpy

assert os.environ["CALCULATOR_MODE"] == "smoke test"
runpy.run_module("stories.tools.server", run_name="__main__")
Path("server exited").touch()
""",
        encoding="utf-8",
    )
    calculator_mode = "smoke test" if version == 2 else "overridden from CLI"
    (work_dir / "specmatic.yaml").write_text(
        f"""version: {version}
mcp:
  test:
    transportKind: STDIO
    command: {json.dumps(sys.executable)}
    args: ["launch calculator.py"]
    workingDirectory: "server files"
    env:
      CALCULATOR_MODE: {json.dumps(calculator_mode)}
""",
        encoding="utf-8",
    )
    overrides = ("--env", "CALCULATOR_MODE=smoke test") if version == 3 else ()
    check_calculator_report(work_dir, run_specmatic(jar, work_dir, *overrides))
    if not (server_dir / "server exited").is_file():
        raise SystemExit("Specmatic did not wait for the calculator to shut down after stdin closed")


def check_crashed_server(jar, work_dir):
    (work_dir / "specmatic.yaml").write_text("version: 2\n", encoding="utf-8")
    (work_dir / "crashing_server.py").write_text(
        """import os

from stories._hosting import run_server_from_args
from stories.tools.server import build_server

def build_crashing_server():
    server = build_server()

    @server.tool()
    def crash(text: str) -> str:
        os._exit(17)

    return server

run_server_from_args(build_crashing_server)
""",
        encoding="utf-8",
    )
    run = run_specmatic(
        jar, work_dir, "--transport-kind", "STDIO", "--command", sys.executable,
        "--arg=crashing_server.py", "--filter-tools=calc,crash",
        capture_output=True, text=True,
    )
    if (run.returncode == 0 or "Test Result: PASSED" not in run.stdout
            or "STDIO MCP server failed during tools/call" not in run.stderr):
        raise SystemExit(f"Expected a failed run after the server crashed:\n{run.stdout}{run.stderr}")
    print("A server crash after a passing scenario fails the run.")


def main():
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {Path(sys.argv[0]).name} <path-to-specmatic-jar>")

    jar = Path(sys.argv[1]).resolve(strict=True)
    checkout = Path(__file__).resolve().parents[4]
    temp_root = checkout / "temp"
    temp_root.mkdir(exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="mcp-stdio-", dir=temp_root) as directory:
        root = Path(directory)
        cli_dir = root / "cli"
        cli_dir.mkdir()
        (cli_dir / "specmatic.yaml").write_text("version: 2\n", encoding="utf-8")
        check_calculator_report(cli_dir, run_specmatic(
            jar, cli_dir, "--transport-kind", "STDIO", "--command", sys.executable,
            "--arg=-m", "--arg=stories.tools.server",
        ))

        for version in (2, 3):
            config_dir = root / f"config-v{version}"
            config_dir.mkdir()
            check_config(jar, config_dir, version)

        crash_dir = root / "crash"
        crash_dir.mkdir()
        check_crashed_server(jar, crash_dir)


if __name__ == "__main__":
    main()
