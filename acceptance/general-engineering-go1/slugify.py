"""Small GO-1 acceptance fixture used to prove unknown-bug engineering autonomy."""

def slugify(value: str) -> str:
    normalized = value.strip().lower()
    return normalized.replace(" ", "-")
