"""Offline manga identity experiment. No Android, network, user data or dependencies.

Run: python tools/manga-research/link_probe.py
Optional: pass a JSON array of records with id, original, artists (tag IDs), language,
pages and display (optional). Prints aggregate metadata only. Not a production classifier.
"""
from collections import Counter, defaultdict
from itertools import combinations
import json
from pathlib import Path
import re
import sqlite3
import sys
import unicodedata


# Only remove a standalone, trailing language annotation. Do not erase edition/title brackets.
LANG_SUFFIX = re.compile(r'\s*\[(?:english|japanese|chinese|russian|英訳|中国翻訳|中国翻译|ロシア翻訳)\]$')
WIDTH = str.maketrans({chr(n): chr(n - 0xFEE0) for n in range(0xFF01, 0xFF5F)})
CHAPTER = re.compile(r'(?:\b(?:ch(?:apter)?\.?|глава)\s*(\d+(?:\.\d+)?)(?![\w.])|第\s*(\d+(?:\.\d+)?)\s*話)')
COMPOSITE = re.compile(r'\b(?:ongoing|omnibus|anthology|complete collection)\b|合本|(?:\bch(?:apter)?\.?\s*|第)\d+(?:\.\d+)?\s*[-–~〜]\s*\d+')


def norm(s):
    return ' '.join(unicodedata.normalize('NFC', s.translate(WIDTH)).lower().split())


def original(row):
    return LANG_SUFFIX.sub('', norm(row['original'])).strip()


def numbers(text):
    return [a or b for a, b in CHAPTER.findall(norm(text))]


def classify(a, b):
    """A preview group is NOT authority for sharing a read mark."""
    if a['id'] == b['id']:
        return 'same-id'
    if not original(a) or original(a) != original(b):
        return 'separate'
    if not a['artists'] or set(a['artists']) != set(b['artists']):
        return 'separate'
    if not a['language'] or not b['language'] or a['language'] == b['language']:
        return 'review'
    # A conflicting chapter in either display title vetoes even a preview merge.
    signatures = {tuple(seq) for row in (a, b) for field in ('original', 'display')
                  if (seq := numbers(row.get(field, '')))}
    return 'review' if len(signatures) > 1 else 'preview'


def preview_groups(rows):
    buckets = defaultdict(list)
    for row in rows:
        if original(row) and row['artists']:
            buckets[original(row), tuple(sorted(set(row['artists'])))].append(row)
    for bucket in buckets.values():
        # A↔B and B↔C are not authority for merging A with C.
        if len(bucket) < 2 or not all(classify(a, b) == 'preview' for a, b in combinations(bucket, 2)):
            continue
        if len({r.get('pages') for r in bucket}) != 1 or not (bucket[0].get('pages') or 0) > 0:
            continue
        if any(COMPOSITE.search(norm(r.get(field, ''))) for r in bucket for field in ('original', 'display')):
            continue
        yield tuple(sorted(r['id'] for r in bucket))


def measure(rows):
    by_title = defaultdict(list)
    for row in rows:
        if original(row):
            by_title[original(row)].append(row)
    pairs = Counter(classify(a, b) for bucket in by_title.values() for a, b in combinations(bucket, 2))
    groups = list(preview_groups(rows))
    return dict(rows=len(rows), original=sum(bool(original(r)) for r in rows),
                artist=sum(bool(r['artists']) for r in rows), language=sum(bool(r['language']) for r in rows),
                title_pairs=sum(pairs.values()), candidates=pairs['preview'], review=pairs['review'], rejected=pairs['separate'],
                groups=len(groups), grouped_editions=sum(map(len, groups)))


def matcher_cases():
    a = dict(id=1, original='星図 第１話', display='Star Map Ch. 1', artists=[17, 29], language='japanese')
    b = dict(a, id=2, original='星図　第1話 [英訳]', display='Atlas — Chapter 1', artists=[29, 17], language='english')
    cases = [
        ('width, language suffix, author order and translated display', a, b, 'preview'),
        ('same gallery', a, a, 'same-id'),
        ('homonym by another artist', a, dict(b, artists=[39]), 'separate'),
        ('one shared coauthor', a, dict(b, artists=[17]), 'separate'),
        ('no resolved artists', dict(a, artists=[]), dict(b, artists=[]), 'separate'),
        ('unknown original, identical display', dict(a, original=''), dict(b, original='', display=a['display']), 'separate'),
        ('chapter 1 versus 10', a, dict(b, original='星図 第10話'), 'separate'),
        ('decimal chapter', a, dict(b, original='星図 第1.5話'), 'separate'),
        ('bonus', a, dict(b, original='星図 第1話 番外編'), 'separate'),
        ('collected edition', a, dict(b, original='星図 第1話 [合本]'), 'separate'),
        ('same-language reissue', a, dict(b, language='japanese'), 'review'),
        ('unknown language', a, dict(b, language=''), 'review'),
        ('conflicting translated chapter', a, dict(b, display='Atlas — глава 2'), 'review'),
        ('two different translations without original', dict(a, original='Карта звёзд'), dict(b, original='Star Atlas'), 'separate'),
        ('meaningful bracket stays', a, dict(b, original='星図 第1話 [Afterword]'), 'separate'),
        ('bare title collides even for the same artist', dict(a, original='星図', display=''), dict(b, original='星図', display=''), 'preview'),
    ]
    for name, left, right, expected in cases:
        assert classify(left, right) == classify(right, left) == expected, name
    a, b = dict(a, pages=24), dict(b, pages=24)
    assert list(preview_groups([b, a])) == [(1, 2)]
    assert not list(preview_groups([a, b, dict(b, id=3)]))  # ambiguous same-language reissue
    assert not list(preview_groups([a, dict(b, pages=25)]))
    assert not list(preview_groups([dict(a, pages=0), dict(b, pages=0)]))
    assert not list(preview_groups([a, dict(b, display='Ch. 1–3')]))
    assert not list(preview_groups([a, dict(b, display='Ch. 1 (ongoing)')]))
    return len(cases)


def reading_cases():
    # Read evidence belongs to the edition where it was recorded. Membership determines projection.
    db = sqlite3.connect(':memory:')
    db.executescript('''
        CREATE TABLE member(gallery INTEGER PRIMARY KEY, work TEXT NOT NULL, unit TEXT NOT NULL);
        CREATE TABLE finished(gallery INTEGER PRIMARY KEY);
        CREATE TABLE progress(gallery INTEGER PRIMARY KEY, page INTEGER NOT NULL);
        CREATE VIEW read_editions AS SELECT DISTINCT b.gallery FROM member a
          JOIN finished f ON f.gallery=a.gallery JOIN member b ON a.work=b.work AND a.unit=b.unit;
        INSERT INTO member VALUES (101,'atlas','1'),(102,'atlas','1'),(103,'atlas','2'),(104,'other','1');
        INSERT INTO progress VALUES (101,16),(102,3);
        INSERT INTO finished VALUES (101);
    ''')
    def read_ids():
        return {r[0] for r in db.execute('SELECT gallery FROM read_editions')}
    assert read_ids() == {101, 102}  # same chapter only, not another work's chapter 1
    assert db.execute('SELECT page FROM progress WHERE gallery=102').fetchone() == (3,)
    def known_chapters():
        return db.execute('''SELECT COUNT(DISTINCT m.unit), COUNT(DISTINCT CASE WHEN r.gallery IS NOT NULL THEN m.unit END)
            FROM member m LEFT JOIN read_editions r ON r.gallery=m.gallery WHERE work='atlas' ''').fetchone()
    assert known_chapters() == (2, 1)
    db.execute('INSERT INTO finished VALUES (103)')
    assert known_chapters() == (2, 2)
    db.execute("INSERT INTO member VALUES (106,'atlas','3')")
    assert known_chapters() == (3, 2)  # a newly found chapter is not read automatically
    db.execute('DELETE FROM finished WHERE gallery=103')
    db.execute("INSERT INTO member VALUES (105,'atlas','1')")  # manually link a differently named translation
    assert read_ids() == {101, 102, 105}
    db.execute("UPDATE member SET work='other', unit='2' WHERE gallery=105")
    assert read_ids() == {101, 102}  # unlink removes inherited completion
    db.execute('INSERT INTO finished VALUES (102)')
    db.execute("UPDATE member SET work='separate' WHERE gallery=102")
    db.execute('DELETE FROM finished WHERE gallery=101')
    assert read_ids() == {102}  # an independently marked translation keeps its own evidence
    assert db.execute('SELECT * FROM progress ORDER BY gallery').fetchall() == [(101,16),(102,3)]
    db.close()


if __name__ == '__main__':
    print(f'matcher: {matcher_cases()} cases passed')
    reading_cases()
    print('SQLite: chapter scope, independent progress, manual link, unlink, independent read passed')
    if len(sys.argv) == 2:
        print(json.dumps(measure(json.loads(Path(sys.argv[1]).read_text(encoding='utf-8')))))
