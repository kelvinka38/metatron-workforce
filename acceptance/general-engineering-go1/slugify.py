"""Small GO-1 acceptance fixture used to prove unknown-bug engineering autonomy."""

def slugify(value: str) -> str:
    import re
    normalized = value.strip().lower()
    return re.sub(r"\s+", "-", normalized)
