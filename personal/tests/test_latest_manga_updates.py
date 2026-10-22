"""Exercise the production updates SQL against an in-memory SQLite database."""
from pathlib import Path
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SQL = (ROOT / "data/src/main/sqldelight/view/updatesView.sq").read_text()


class LatestMangaUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE mangas (_id INTEGER PRIMARY KEY, title TEXT, source INTEGER,
                favorite INTEGER, thumbnail_url TEXT, cover_last_modified INTEGER, date_added INTEGER,
                status INTEGER NOT NULL DEFAULT 1);
            CREATE TABLE chapters (_id INTEGER PRIMARY KEY, manga_id INTEGER, name TEXT,
                scanlator TEXT, read INTEGER, bookmark INTEGER, last_page_read INTEGER,
                date_upload INTEGER, date_fetch INTEGER, source_order INTEGER);
        """)
        self.db.executescript(SQL.split("getRecentUpdates:")[0])
        self.query = SQL.split("getRecentUpdates:")[1].split("getUpdatesByReadStatus:")[0].strip()
        self.add_manga(1)

    def tearDown(self):
        self.db.close()

    def add_manga(self, manga_id, favorite=1, date_added=10, source=1, status=1):
        self.db.execute("INSERT INTO mangas VALUES (?, ?, ?, ?, NULL, 0, ?, ?)",
                        (manga_id, str(manga_id), source, favorite, date_added, status))

    def chapter(self, chapter_id, order, manga_id=1, fetched=100, uploaded=100, read=0, name=None):
        self.db.execute("INSERT INTO chapters VALUES (?, ?, ?, NULL, ?, 0, 0, ?, ?, ?)",
                        (chapter_id, manga_id, name or str(chapter_id), read, uploaded, fetched, order))

    def ids(self, after=0, limit=500):
        rows = self.db.execute(self.query, {"after": after, "limit": limit})
        return [row[2] for row in rows]

    def test_bulk_fetch_only_shows_latest_chapter(self):
        for i in range(1, 60):
            self.chapter(i, 60 - i)
        self.assertEqual([59], self.ids())

    def test_source_order_wins_over_late_fetch_of_old_chapter(self):
        self.chapter(1, 0, fetched=100)
        self.chapter(2, 1, fetched=300, uploaded=500)
        self.assertEqual([1], self.ids())

    def test_library_only_and_each_work_has_its_own_latest(self):
        self.add_manga(2)
        self.add_manga(3, favorite=0)
        self.chapter(1, 0)
        self.chapter(2, 0, manga_id=2, fetched=200)
        self.chapter(3, 0, manga_id=3, fetched=300)
        self.assertEqual([2, 1], self.ids())

    def test_initial_fetch_before_library_registration_stays_excluded(self):
        self.chapter(1, 0, fetched=10)
        self.assertEqual([], self.ids())

    def test_latest_read_chapter_does_not_fall_back_to_old_unread(self):
        self.chapter(1, 0, read=1)
        self.chapter(2, 1)
        self.assertEqual([1], self.ids())

    def test_recently_discovered_latest_is_visible_even_with_an_old_upload_date(self):
        self.chapter(1, 0, uploaded=20)
        self.chapter(2, 1, uploaded=100)
        self.assertEqual([1], self.ids(after=50))

    def test_duplicate_source_order_has_deterministic_single_winner(self):
        self.chapter(1, 0)
        self.chapter(2, 0)
        self.assertEqual([2], self.ids())

    def test_limit_applies_after_choosing_one_chapter_per_work(self):
        for i in range(1, 602):
            self.chapter(i, i - 1, fetched=1000, uploaded=1000)
        self.add_manga(2)
        self.chapter(1000, 0, manga_id=2, fetched=100)
        self.assertEqual([1, 1000], self.ids())
        self.assertEqual([1], self.ids(limit=1))

    def test_latest_collected_before_registration_does_not_fall_back_to_backlog(self):
        self.chapter(1, 0, fetched=5)
        self.chapter(2, 1, fetched=200)
        self.assertEqual([], self.ids())

    def test_date_limit_is_discovery_time_when_source_upload_is_old(self):
        self.chapter(1, 0, uploaded=20, fetched=300)
        self.assertEqual([1], self.ids(after=50))

    def test_full_500_work_limit_is_deterministic(self):
        for manga_id in range(1, 506):
            if manga_id != 1:
                self.add_manga(manga_id)
            self.chapter(manga_id * 10, 0, manga_id=manga_id, fetched=100)
            self.chapter(manga_id * 10 + 1, 1, manga_id=manga_id, fetched=200)
        expected = [manga_id * 10 for manga_id in range(505, 5, -1)]
        self.assertEqual(expected, self.ids())
        self.assertEqual(expected, self.ids())

    def test_query_preserves_all_chapters_and_reading_progress(self):
        self.chapter(1, 0, read=1)
        self.chapter(2, 1)
        self.db.execute("UPDATE chapters SET bookmark=1, last_page_read=17 WHERE _id=2")
        before = self.db.execute("SELECT * FROM chapters ORDER BY _id").fetchall()
        changes = self.db.total_changes
        self.assertEqual([1], self.ids())
        self.assertEqual(before, self.db.execute("SELECT * FROM chapters ORDER BY _id").fetchall())
        self.assertEqual(changes, self.db.total_changes)

    def test_discovery_cutoff_is_strict_even_with_a_recent_upload(self):
        self.chapter(1, 0, uploaded=500, fetched=50)
        self.assertEqual([], self.ids(after=50))
        self.assertEqual([1], self.ids(after=49))

    def test_read_status_query_remains_independent_of_latest_only_display(self):
        self.chapter(1, 0, read=1)
        self.chapter(2, 1, read=0)
        query = SQL.split("getUpdatesByReadStatus:")[1].strip()
        unread = self.db.execute(query, {"read": 0, "after": 0, "limit": 500}).fetchall()
        self.assertEqual([2], [row[2] for row in unread])
        self.assertEqual([1], self.ids())


    # Dtoon9.2: Naver locked episodes, finished works and release-date order.
    NAVER = 850164079243120301

    def test_locked_episodes_never_become_the_latest(self):
        self.chapter(1, 0, name="12 - 🔒 미리보기")
        self.chapter(2, 1, name="11 - 열린 회차")
        self.assertEqual([2], self.ids())

    def test_only_locked_episodes_show_nothing(self):
        self.chapter(1, 0, name="🔒 1")
        self.assertEqual([], self.ids())

    def test_finished_cancelled_and_paused_works_are_hidden(self):
        for manga_id, status in [(2, 2), (3, 4), (4, 5), (5, 6), (6, 0)]:
            self.add_manga(manga_id, status=status)
            self.chapter(manga_id * 10, 0, manga_id=manga_id)
        self.chapter(1, 0)
        # Ongoing (1) and unknown (0) remain; completed, finished, cancelled and hiatus do not.
        self.assertEqual([60, 1], self.ids())

    def test_naver_episode_opened_after_registration_appears_by_release_date(self):
        self.add_manga(2, source=self.NAVER, date_added=50)
        # Stored as a locked preview before registration, opened (renamed, re-dated) later.
        self.chapter(20, 0, manga_id=2, fetched=40, uploaded=300, name="5 - 열림")
        self.assertEqual([20], self.ids())
        self.assertEqual([], self.ids(after=300))

    def test_other_sources_still_need_discovery_after_registration(self):
        self.chapter(1, 0, fetched=5, uploaded=300)
        self.assertEqual([], self.ids())

    def test_order_follows_release_date(self):
        self.add_manga(2, source=self.NAVER)
        self.chapter(1, 0, fetched=500, uploaded=200)
        self.chapter(20, 0, manga_id=2, fetched=100, uploaded=400)
        self.assertEqual([20, 1], self.ids())

    def test_a_future_upload_date_never_moves_other_sources_ahead(self):
        self.add_manga(2)
        self.chapter(1, 0, fetched=300, uploaded=300)
        self.chapter(20, 0, manga_id=2, fetched=200, uploaded=9999)
        self.assertEqual([1, 20], self.ids())


if __name__ == "__main__":
    unittest.main()
