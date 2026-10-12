from datetime import datetime, timezone

EPOCH = "1970-01-01T00:00:00Z"


def format_instant(value: datetime) -> str:
    moment = value.astimezone(timezone.utc)
    if moment.microsecond:
        fractional = moment.strftime("%Y-%m-%dT%H:%M:%S.%f").rstrip("0")
        return f"{fractional}Z"
    return moment.strftime("%Y-%m-%dT%H:%M:%S") + "Z"


def parse_instant(value: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
