import logging

from starlette.responses import Response

logger = logging.getLogger("versionserver.access")
error_log = logging.getLogger("versionserver")


class AccessLogMiddleware:
    """Log each HTTP call after the response status is known.

    Implemented as raw ASGI middleware so streamed downloads are not buffered.
    """

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        status_code = 500

        async def send_wrapper(message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception:
            error_log.exception(
                "unhandled %s %s",
                scope.get("method"),
                scope.get("path"),
            )
            await Response(status_code=500)(scope, receive, send)
            status_code = 500
        logger.info("%s %s -> %s", scope.get("method"), scope.get("path"), status_code)
