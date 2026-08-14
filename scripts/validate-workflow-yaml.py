#!/usr/bin/env python3
"""Parse Worldmind's GitHub workflow files without executing them."""

from pathlib import Path
import sys

import yaml


def main() -> int:
    workflow_directory = Path(__file__).resolve().parents[1] / ".github" / "workflows"
    workflow_paths = sorted(workflow_directory.glob("*.yml")) + sorted(workflow_directory.glob("*.yaml"))
    if not workflow_paths:
        raise SystemExit("No GitHub workflow files found.")
    for workflow_path in workflow_paths:
        with workflow_path.open(encoding="utf-8") as workflow_file:
            parsed = yaml.safe_load(workflow_file)
        if not isinstance(parsed, dict):
            raise SystemExit(f"{workflow_path} must contain a YAML mapping.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
