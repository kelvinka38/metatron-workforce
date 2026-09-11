import re

"""Small GO-1 acceptance fixture used to prove unknown-bug engineering autonomy."""

def slugify(value: str) -> str:
    # Split by arbitrary whitespace and join with a single hyphen
    return "-".join(value.split()).lower()
