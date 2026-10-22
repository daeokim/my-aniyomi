"""Production anime update view/query and v139 migration against SQLite."""
from pathlib import Path
import sqlite3,unittest
ROOT=Path(__file__).resolve().parents[2]
SQL=(ROOT/'data/src/main/sqldelightanime/view/animeupdatesView.sq').read_text()
VIEW=SQL.split('getRecentAnimeUpdates:')[0]
QUERY=SQL.split('getRecentAnimeUpdates:')[1].split('getUpdatesBySeenStatus:')[0].strip()
SCHEMA='''CREATE TABLE animes(_id INTEGER PRIMARY KEY,title TEXT,source INTEGER,favorite INTEGER,thumbnail_url TEXT,cover_last_modified INTEGER,date_added INTEGER,parent_id INTEGER);
CREATE TABLE episodes(_id INTEGER PRIMARY KEY,anime_id INTEGER,name TEXT,scanlator TEXT,seen INTEGER,bookmark INTEGER,fillermark INTEGER,last_second_seen INTEGER,total_seconds INTEGER,date_upload INTEGER,date_fetch INTEGER);'''
class AnimeUpdatesTest(unittest.TestCase):
 def setUp(self):
  self.db=sqlite3.connect(':memory:');self.db.executescript(SCHEMA);self.db.executescript(VIEW)
 def tearDown(self):self.db.close()
 def work(self,id,favorite=1,parent=None,added=10):self.db.execute('INSERT INTO animes VALUES(?,?,1,?,NULL,0,?,?)',(id,str(id),favorite,added,parent))
 def episode(self,id,work=1,fetched=100,uploaded=100):self.db.execute('INSERT INTO episodes VALUES(?,?,?,NULL,0,1,0,17000,600000,?,?)',(id,work,str(id),uploaded,fetched))
 def ids(self,after=0,limit=500):return [x[2] for x in self.db.execute(QUERY,dict(after=after,limit=limit))]
 def test_recent_discovery_is_visible_with_zero_or_stale_upload_dates(self):
  self.work(1);self.episode(1,uploaded=0);self.episode(2,fetched=200,uploaded=5)
  self.assertEqual([2,1],self.ids(after=50))
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
  self.work(1);self.episode(1);self.episode(2)
  self.assertEqual([],self.ids(after=100));self.assertEqual([2,1],self.ids(after=99));self.assertEqual([2],self.ids(limit=1))
 def test_existing_database_migration_matches_fresh_schema_without_touching_progress(self):
  db=sqlite3.connect(':memory:');db.executescript(SCHEMA)
  db.executescript((ROOT/'personal/tests/fixtures/animeupdates-v138.sql').read_text())
  db.execute('INSERT INTO animes VALUES(1,"parent",1,1,NULL,0,10,NULL)')
  db.execute('INSERT INTO animes VALUES(2,"season",1,0,NULL,0,10,1)')
  db.execute('INSERT INTO episodes VALUES(1,2,"1",NULL,1,1,1,17000,600000,0,100)')
  self.assertEqual([],db.execute('SELECT * FROM animeupdatesView').fetchall())
  before=db.execute('SELECT * FROM episodes').fetchall()
  db.executescript((ROOT/'data/src/main/sqldelightanime/migrations/139.sqm').read_text())
  self.assertEqual([1],[x[2] for x in db.execute(QUERY,dict(after=50,limit=500))])
  self.assertEqual(before,db.execute('SELECT * FROM episodes').fetchall())
  self.assertEqual(db.execute("SELECT sql FROM sqlite_master WHERE name='animeupdatesView'").fetchone()[0],self.db.execute("SELECT sql FROM sqlite_master WHERE name='animeupdatesView'").fetchone()[0]);db.close()
if __name__=='__main__':unittest.main()
