# config.py
"""Configuration and environment loader for Solips face-service.

Provides robust, cwd-independent loading of the shared repository-root .env file
and optional service-local .env, matching Spring Boot configuration imports.
Preserves existing OS environment variables over file-defined values.
"""

import os
from pathlib import Path
from typing import Iterable, List, Optional, Union

try:
    from dotenv import load_dotenv as _dotenv_load
except ImportError:
    _dotenv_load = None


def get_default_env_paths() -> List[Path]:
    """Return default candidate paths for .env matching Spring Boot import conventions

    and repository structure in precedence order:
    1. Working directory .env (cwd / .env)
    2. Parent of working directory .env (cwd.parent / .env)
    3. Service root directory .env (face-service / .env)
    4. Repository root directory .env (Solips / .env)
    """
    service_dir = Path(__file__).resolve().parent
    repo_root = service_dir.parent

    candidates = [
        Path.cwd() / ".env",
        Path.cwd().parent / ".env",
        service_dir / ".env",
        repo_root / ".env",
    ]

    deduped: List[Path] = []
    seen = set()
    for p in candidates:
        try:
            resolved = p.resolve()
        except Exception:
            resolved = p
        if resolved not in seen:
            seen.add(resolved)
            deduped.append(p)
    return deduped


def parse_env_file(file_path: Path) -> dict:
    """Minimal safe .env parser used when python-dotenv is absent or for isolated parsing."""
    env_vars = {}
    try:
        content = file_path.read_text(encoding="utf-8")
        for line in content.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, val = line.split("=", 1)
                key = key.strip()
                val = val.strip()
                if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
                    val = val[1:-1]
                if key:
                    env_vars[key] = val
    except Exception:
        pass
    return env_vars


def load_environment(
    search_paths: Optional[Iterable[Union[str, Path]]] = None,
    override: bool = False,
    use_dotenv: bool = True,
) -> List[Path]:
    """Optionally load environment variables from candidate .env files.

    - By default, existing os.environ variables override file values (override=False).
    - CWD-independent resolution ensures repository root .env is found.
    - If python-dotenv is available and use_dotenv is True, uses load_dotenv.
    - Otherwise falls back to minimal safe built-in parser.
    - Missing files are ignored safely without error.
    - Returns a list of paths that actually existed and were loaded.
    """
    if search_paths is None:
        paths = get_default_env_paths()
    else:
        paths = [Path(p) for p in search_paths]

    loaded: List[Path] = []
    for path in paths:
        if path.is_file():
            if use_dotenv and _dotenv_load is not None:
                _dotenv_load(dotenv_path=path, override=override)
            else:
                parsed = parse_env_file(path)
                for k, v in parsed.items():
                    if override or k not in os.environ:
                        os.environ[k] = v
            loaded.append(path)

    return loaded
