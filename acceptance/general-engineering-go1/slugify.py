import re

"""Small GO-1 acceptance fixture used to prove unknown-bug engineering autonomy."""

def slugify(value: str) -> str:
    # Replace whitespace characters (including \t, \n) with spaces, split, filter empty, join with hyphen
    normalized = re.sub(r'\s+', '-', value.strip())
    # To handle the specific requirement of multiple hyphens if not already handled
    # The tests expect "hello-metatron-world" for "Hello   Metatron   World"
    # Simply splitting by whitespace is more robust
    parts = value.split()
    return "-".join(parts).lower()
