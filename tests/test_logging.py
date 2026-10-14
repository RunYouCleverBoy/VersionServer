import logging
import sys
from logging.handlers import TimedRotatingFileHandler

from versionserver.logging_config import configure_logging, use_process_logging


class _Capture(logging.Handler):
    def __init__(self) -> None:
        super().__init__()
        self.records: list[logging.LogRecord] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.records.append(record)


def _restore(root: logging.Logger, handlers: list[logging.Handler], level: int) -> None:
    for handler in root.handlers:
        if handler not in handlers:
            handler.close()
    root.handlers = handlers
    root.setLevel(level)
    logging.getLogger("versionserver").setLevel(logging.NOTSET)


def test_vercel_handler_keeps_level_and_message():
    root = logging.getLogger()
    previous = list(root.handlers)
    previous_level = root.level
    capture = _Capture()
    root.handlers = [capture]
    try:
        use_process_logging()
        logging.getLogger("versionserver.access").info("GET /ui/ -> 200")
        logging.getLogger("versionserver").error("startup failed")
        assert root.handlers == [capture]
        assert [(record.levelname, record.getMessage()) for record in capture.records] == [
            ("INFO", "GET /ui/ -> 200"),
            ("ERROR", "startup failed"),
        ]
        assert not any(isinstance(handler, TimedRotatingFileHandler) for handler in root.handlers)
    finally:
        _restore(root, previous, previous_level)


def test_missing_handler_writes_info_to_stdout_and_errors_to_stderr(capsys):
    root = logging.getLogger()
    previous = list(root.handlers)
    previous_level = root.level
    root.handlers = []
    try:
        use_process_logging()
        logging.getLogger("versionserver.access").info("served page")
        logging.getLogger("versionserver").warning("slow storage")
        logging.getLogger("versionserver").error("startup failed")
        captured = capsys.readouterr()
        assert "served page" in captured.out
        assert "slow storage" in captured.out
        assert "startup failed" not in captured.out
        assert "startup failed" in captured.err
        assert "served page" not in captured.err
    finally:
        _restore(root, previous, previous_level)


def test_server_logging_still_writes_a_file(tmp_path):
    root = logging.getLogger()
    previous = list(root.handlers)
    previous_level = root.level
    try:
        configure_logging(tmp_path)
        logging.getLogger("versionserver").info("ready")
        assert (tmp_path / "versionserver.log").is_file()
        assert any(isinstance(handler, TimedRotatingFileHandler) for handler in root.handlers)
        assert any(getattr(handler, "stream", None) is sys.stdout for handler in root.handlers)
        assert any(getattr(handler, "stream", None) is sys.stderr for handler in root.handlers)
    finally:
        _restore(root, previous, previous_level)
