"""Small GO-1 acceptance fixture used to prove unknown-bug engineering autonomy."""

import re

def slugify(value: str) -> str:
    normalized = value.strip().lower()
    return re.sub(r"\s+", "-", normalized)
