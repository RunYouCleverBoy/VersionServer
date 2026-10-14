import logging
import sys
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path


class _AtMost(logging.Filter):
    def __init__(self, level: int) -> None:
        super().__init__()
        self._level = level

    def filter(self, record: logging.LogRecord) -> bool:
        return record.levelno <= self._level


_CONSOLE_FORMAT = logging.Formatter(
    "%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%H:%M:%S",
)


def _stdio_handlers() -> list[logging.Handler]:
    stdout = logging.StreamHandler(sys.stdout)
    stdout.setLevel(logging.INFO)
    stdout.addFilter(_AtMost(logging.WARNING))
    stdout.setFormatter(_CONSOLE_FORMAT)

    stderr = logging.StreamHandler(sys.stderr)
    stderr.setLevel(logging.ERROR)
    stderr.setFormatter(_CONSOLE_FORMAT)
    return [stdout, stderr]


def use_process_logging() -> None:
    """Publish versionserver records on the process logger.

    Vercel installs a root handler before it imports the app and classifies
    each standard-library record by level. Those records are left on that
    handler. No Vercel package is required. When the process has no handler
    yet, INFO and WARNING go to stdout and ERROR and above go to stderr.
    """
    logging.getLogger("versionserver").setLevel(logging.DEBUG)
    root = logging.getLogger()
    if root.level == logging.NOTSET or root.level > logging.INFO:
        root.setLevel(logging.INFO)
    if root.handlers:
        return
    for handler in _stdio_handlers():
        root.addHandler(handler)


def configure_logging(log_dir: Path) -> None:
    log_dir.mkdir(parents=True, exist_ok=True)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.handlers.clear()
    for handler in _stdio_handlers():
        root.addHandler(handler)

    file_handler = TimedRotatingFileHandler(
        log_dir / "versionserver.log",
        when="midnight",
        backupCount=14,
        encoding="utf-8",
    )
    file_handler.setFormatter(
        logging.Formatter(
            "%(asctime)s [%(levelname)s] %(name)s - %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S%z",
        )
    )
    root.addHandler(file_handler)
    logging.getLogger("versionserver").setLevel(logging.DEBUG)
