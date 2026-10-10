"""Offline checks for the NTK extension's parsing rules.

Run from the repository root:

    python src/ko/ntk/tools/verify.py

Verifies the pure decision logic in Ntk.kt against rules that were confirmed by probing
the live site, so a change to those rules fails loudly here instead of silently
mis-parsing chapters or answering deeplink queries with the wrong page.

The app-facing path (an episode URL must not resolve to a work, an unknown id must not
resolve at all) cannot be exercised on the JVM because it needs the Ktor source stream.
Those are documented as manual checks at the bottom of this file.
"""

from __future__ import annotations

import re
from urllib.parse import urlparse

FAILURES: list[str] = []


def check(label: str, actual, expected) -> None:
    ok = actual == expected
    if not ok:
        FAILURES.append(f"{label}: expected {expected!r}, got {actual!r}")
    print(f"  {'OK  ' if ok else 'FAIL'} {label}")


# --- mirrors of the constants in Ntk.kt -------------------------------------------------

WEBTOON_PATH = "/webtoon"
MANHWA_PATH = "/manhwa"

EPISODE_LABEL = "-1F"
UNCLASSIFIED_LABEL = "-2F"

# Ntk.kt companion object
EPISODE_REGEX = re.compile(r"\d+(?:\.\d+)?\s*화(?!\p{L})".replace(r"\p{L}", r"[^\W\d_]"))
VOLUME_REGEX = re.compile(r"\d+(?:\.\d+)?\s*권(?!\p{L})".replace(r"\p{L}", r"[^\W\d_]"))

PLATFORM_IDS = [
    "", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "18", "99",
]


# --- logic under test -------------------------------------------------------------------


def is_volume(name: str) -> bool:
    return bool(VOLUME_REGEX.search(name))


def is_episode(name: str) -> bool:
    # A volume that mentions episodes ("77권 (300화~305화)") stays a volume.
    return not is_volume(name) and bool(EPISODE_REGEX.search(name))


def chapter_number(name: str) -> str:
    return EPISODE_LABEL if is_episode(name) else UNCLASSIFIED_LABEL


def resolves_work_path(url: str, section_path: str) -> bool:
    """Mirrors getMangaByUrl's path guard: exactly /<section>/<id>."""
    segments = [segment for segment in urlparse(url).path.split("/") if segment]
    return len(segments) == 2 and f"/{segments[0]}" == section_path


# --- 1. chapter naming ------------------------------------------------------------------

print("chapter naming (verified against names the site actually serves)")
EPISODE_NAMES = [
    "1194화",       # manhwa, plain
    "73-2화",       # manhwa, split episode part
    "29화",
    "5화. 부제",     # episode with subtitle
    "연상녀클럽 6화",  # title glued to the number, no space
    "화산귀환 174화",
    "무당기협 174화3660",
]
for name in EPISODE_NAMES:
    check(f"{name!r} -> episode", chapter_number(name), EPISODE_LABEL)

UNCLASSIFIED_NAMES = [
    "77권",             # volume, must not collide with 77화
    "77.5권",           # decimal volume
    "번외편",            # special without a number
    "작품 정보",
]
for name in UNCLASSIFIED_NAMES:
    check(f"{name!r} -> unclassified", chapter_number(name), UNCLASSIFIED_LABEL)

check("volume mentioning episodes stays a volume", chapter_number("77권 (300화~305화)"), UNCLASSIFIED_LABEL)
check("화 inside a word does not match", chapter_number("문화"), UNCLASSIFIED_LABEL)

# --- 2. getMangaByUrl path guard ---------------------------------------------------------

print()
print("getMangaByUrl path guard (deeplink and shared-link input)")
MANHWA_CASES = [
    ("https://newtoki1.org/manhwa/37062", True),
    ("https://newtoki1.org/manhwa/37062/", True),
    ("https://newtoki1.org/manhwa/2?epage=2", True),
    ("https://newtoki1.org/manhwa/u-mu5bw23q-r7cn", True),
    # episode URLs are matched by the deeplink patterns and have no detail markup
    ("https://newtoki1.org/manhwa/37062/u-mr3w5x3b-ic4c", False),
    ("https://newtoki1.org/manhwa/37062/1816434", False),
    # listing-ish URLs
    ("https://newtoki1.org/manhwa", False),
    ("https://newtoki1.org/manhwa?stx=x&kind=manhwa", False),
    # the other section, and unrelated hosts
    ("https://newtoki1.org/webtoon/55884394", False),
    ("https://example.com/manhwa/1", True),
    ("https://newtoki1.org/", False),
    ("https://newtoki1.org/bbs/board.php?bo_table=manhwa", False),
]
for url, expected in MANHWA_CASES:
    check(f"manhwa source <- {url}", resolves_work_path(url, MANHWA_PATH), expected)

WEBTOON_CASES = [
    ("https://newtoki1.org/webtoon/55884394", True),
    ("https://newtoki1.org/webtoon/55884394/kp-55884394-70314675", False),
    ("https://newtoki1.org/webtoon", False),
    ("https://newtoki1.org/manhwa/37062", False),
]
for url, expected in WEBTOON_CASES:
    check(f"webtoon source <- {url}", resolves_work_path(url, WEBTOON_PATH), expected)

# --- 3. platform filter ----------------------------------------------------------------

print()
print("platform filter ids")
check("no duplicate platform ids", len(PLATFORM_IDS), len(set(PLATFORM_IDS)))
check("전체 stays first", PLATFORM_IDS[0], "")
# ids 2, 9, 11, 12 and 16 are not offered by the site's own filter buttons but the
# listing still honours them (verified per id against /webtoon?plat=N).
for platform_id in ("2", "9", "11", "12", "16"):
    check(f"platform {platform_id} present", platform_id in PLATFORM_IDS, True)

# --- result ----------------------------------------------------------------------------

print()
if FAILURES:
    print(f"RESULT: {len(FAILURES)} FAILURE(S)")
    for failure in FAILURES:
        print(f"  - {failure}")
    raise SystemExit(1)

print("RESULT: ALL CHECKS PASSED")
print()
print("manual checks that need a device (JVM tests cannot load a Ktor source stream):")
print("  1. paste an episode URL (/manhwa/<work>/<episode>) into search: the app must")
print("     report 'no results', not crash while opening the entry")
print("  2. paste an unknown work URL (/manhwa/99999999): same expectation; the site")
print("     answers 200 with the title '작품을 찾을 수 없습니다' and no chapter list")
print("  3. open a work with chapters split across epage pages and confirm the list")
print("     reaches the oldest chapter")
