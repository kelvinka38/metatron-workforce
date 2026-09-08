import re

"""Small GO-1 acceptance fixture used to prove unknown-bug engineering autonomy."""

def slugify(value: str) -> str:
    return re.sub(r'\s+', '-', value.strip().lower())
