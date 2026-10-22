"""Production anime update view/query and v139 migration against SQLite."""
from pathlib import Path
import sqlite3,unittest
ROOT=Path(__file__).resolve().parents[2]
SQL=(ROOT/'data/src/main/sqldelightanime/view/animeupdatesView.sq').read_text()
VIEW=SQL.split('getRecentAnimeUpdates:')[0]
QUERY=SQL.split('getRecentAnimeUpdates:')[1].split('getUpdatesBySeenStatus:')[0].strip()
SEEN_QUERY=SQL.split('getUpdatesBySeenStatus:')[1].strip()
SCHEMA='''CREATE TABLE animes(_id INTEGER PRIMARY KEY,title TEXT,source INTEGER,favorite INTEGER,thumbnail_url TEXT,cover_last_modified INTEGER,date_added INTEGER,parent_id INTEGER,status INTEGER NOT NULL DEFAULT 1);
CREATE TABLE episodes(_id INTEGER PRIMARY KEY,anime_id INTEGER,name TEXT,scanlator TEXT,seen INTEGER,bookmark INTEGER,fillermark INTEGER,last_second_seen INTEGER,total_seconds INTEGER,date_upload INTEGER,date_fetch INTEGER,source_order INTEGER NOT NULL DEFAULT 0);'''
class AnimeUpdatesTest(unittest.TestCase):
 def setUp(self):
  self.db=sqlite3.connect(':memory:');self.db.executescript(SCHEMA);self.db.executescript(VIEW)
 def tearDown(self):self.db.close()
 def work(self,id,favorite=1,parent=None,added=10,status=1):self.db.execute('INSERT INTO animes VALUES(?,?,1,?,NULL,0,?,?,?)',(id,str(id),favorite,added,parent,status))
 def episode(self,id,work=1,fetched=100,uploaded=100,order=0,seen=0):self.db.execute('INSERT INTO episodes VALUES(?,?,?,NULL,?,1,0,17000,600000,?,?,?)',(id,work,str(id),seen,uploaded,fetched,order))
 def ids(self,after=0,limit=500):return [x[2] for x in self.db.execute(QUERY,dict(after=after,limit=limit))]
 def test_recent_discovery_is_visible_with_zero_or_stale_upload_dates(self):
  self.work(1);self.work(2);self.episode(1,uploaded=0);self.episode(2,work=2,fetched=200,uploaded=5)
  self.assertEqual([1,2],self.ids(after=50))
 def test_unfavorited_season_belongs_to_a_favorited_parent(self):
  self.work(1);self.work(2,favorite=0,parent=1);self.episode(1,work=2)
  self.assertEqual([1],self.ids())
 def test_child_favorite_and_parent_favorite_do_not_duplicate_an_episode(self):
  self.work(1);self.work(2,parent=1);self.episode(1,work=2)
  self.assertEqual([1],self.ids())
 def test_removing_parent_from_library_removes_nonfavorite_season_updates(self):
  self.work(1,favorite=0);self.work(2,favorite=0,parent=1);self.episode(1,work=2)
  self.assertEqual([],self.ids())
 def test_initial_fetch_before_registration_is_not_reported_as_new(self):
  self.work(1,added=100);self.work(2,favorite=0,parent=1);self.episode(1,work=2,fetched=100)
  self.assertEqual([],self.ids())
 def test_horizon_and_equal_time_order_are_deterministic(self):
  self.work(1);self.work(2);self.episode(1);self.episode(2,work=2)
  self.assertEqual([],self.ids(after=100));self.assertEqual([2,1],self.ids(after=99));self.assertEqual([2],self.ids(limit=1))
 def test_existing_database_migration_matches_fresh_schema_without_touching_progress(self):
  db=sqlite3.connect(':memory:');db.executescript(SCHEMA)
  db.executescript((ROOT/'personal/tests/fixtures/animeupdates-v138.sql').read_text())
  db.execute('INSERT INTO animes VALUES(1,"parent",1,1,NULL,0,10,NULL,1)')
  db.execute('INSERT INTO animes VALUES(2,"season",1,0,NULL,0,10,1,1)')
  db.execute('INSERT INTO episodes VALUES(1,2,"1",NULL,1,1,1,17000,600000,0,100,0)')
  self.assertEqual([],db.execute('SELECT * FROM animeupdatesView').fetchall())
  before=db.execute('SELECT * FROM episodes').fetchall()
  db.executescript((ROOT/'data/src/main/sqldelightanime/migrations/139.sqm').read_text())
  self.assertEqual([1],[x[2] for x in db.execute(QUERY,dict(after=50,limit=500))])
  self.assertEqual(before,db.execute('SELECT * FROM episodes').fetchall())
  self.assertEqual(db.execute("SELECT sql FROM sqlite_master WHERE name='animeupdatesView'").fetchone()[0],self.db.execute("SELECT sql FROM sqlite_master WHERE name='animeupdatesView'").fetchone()[0]);db.close()
 # Dtoon9.3: same rules as manga updates.
 def test_only_the_newest_episode_of_a_work_is_listed(self):
  self.work(1)
  for i in range(1,30):self.episode(i,order=30-i)
  self.assertEqual([29],self.ids())
 def test_finished_cancelled_and_paused_works_are_hidden(self):
  for id,status in [(1,2),(2,4),(3,5),(4,6),(5,1),(6,0)]:self.work(id,status=status);self.episode(id,work=id)
  self.assertEqual([6,5],self.ids())
 def test_order_follows_release_date_but_never_after_discovery(self):
  self.work(1);self.work(2);self.work(3)
  self.episode(1,work=1,fetched=500,uploaded=200);self.episode(2,work=2,fetched=300,uploaded=300);self.episode(3,work=3,fetched=250,uploaded=9999)
  self.assertEqual([2,3,1],self.ids())
 def test_widget_query_lists_unseen_latest_episodes_only(self):
  self.work(1);self.work(2)
  self.episode(1,work=1,order=0,seen=1);self.episode(2,work=1,order=1,seen=0)
  self.episode(3,work=2,order=0,seen=0)
  unseen=[x[2] for x in self.db.execute(SEEN_QUERY,dict(seen=0,after=0,limit=500))]
  self.assertEqual([3],unseen)
if __name__=='__main__':unittest.main()
