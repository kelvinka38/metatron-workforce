import re


def slugify(value: str) -> str:
    """Small GO-1 acceptance fixture used to prove unknown-bug engineering autonomy."""
    normalized = value.strip().lower()
    return re.sub(r'\s+', '-', normalized)
