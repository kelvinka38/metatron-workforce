import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from slugify import slugify


def test_collapses_consecutive_spaces_to_one_separator():
    assert slugify("Hello   Metatron   World") == "hello-metatron-world"


def test_collapses_mixed_internal_whitespace_to_one_separator():
    assert slugify("  Hello\t  Metatron\nWorld  ") == "hello-metatron-world"


def test_keeps_alphanumeric_content_and_trims_edges():
    assert slugify("  Metatron 2026  ") == "metatron-2026"
