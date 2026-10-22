#!/usr/bin/env python3
"""SQLite identity round trip + the compiled production EpisodeMapping rules.
This fixture is not the Android protobuf BackupRestorer integration test.
"""
import json, os, pathlib, sqlite3, subprocess, unittest
root=pathlib.Path(__file__).resolve().parents[2]
cp=str(root/'personal/test-output/domain')+os.pathsep+os.pathsep.join(str(p) for p in pathlib.Path(os.environ['JVM_DEPS_DIR']).resolve().glob('*.jar'))
def probe(action, works, prefs=None):
    p=subprocess.run(['java','-cp',cp,'dtoon.validation.EpisodeMappingProbe'],input=json.dumps(dict(action=action,works=works,preferences=prefs or {},chapter='/a/1')),text=True,capture_output=True,check=True)
    return json.loads(p.stdout)
def database(offset=0):
    db=sqlite3.connect(':memory:')
    db.executescript('CREATE TABLE works(id INTEGER PRIMARY KEY AUTOINCREMENT,source INTEGER,url TEXT,UNIQUE(source,url)); CREATE TABLE chapters(work INTEGER,url TEXT,read INTEGER,bookmark INTEGER,page INTEGER,position INTEGER);')
    for n in range(offset):db.execute('INSERT INTO works(source,url) VALUES(?,?)',(999,f'/existing/{n}'))
    for source,url,chapter in [(11,'/work/a','/a/1'),(22,'/work/b','/b/1')]:
        db.execute('INSERT INTO works(source,url) VALUES(?,?)',(source,url));i=db.execute('SELECT last_insert_rowid()').fetchone()[0]
        db.execute('INSERT INTO chapters VALUES(?,?,?,?,?,?)',(i,chapter,1,1,17,325000))
    return db

def works(db):
    return [dict(manga=True,id=i,source=s,url=u,chapters=[r[0] for r in db.execute('SELECT url FROM chapters WHERE work=?',(i,))]) for i,s,u in db.execute('SELECT id,source,url FROM works WHERE source !=999 ORDER BY source')]

class MappingRestore(unittest.TestCase):
    def test_empty_and_existing_database_ids_restore_and_progress_is_unchanged(self):
        original=database(5); base=works(original)
        legacy=probe('legacy',base)['preferences']
        prepared=probe('backup',base,legacy)['preferences']
        for offset in [0,40]:
            with self.subTest(existing_records=offset):
                db=database(offset); restored=works(db)
                self.assertNotEqual([w['id'] for w in base],[w['id'] for w in restored])
                before=list(db.execute('SELECT * FROM chapters'))
                self.assertNotIn('target',probe('restore',restored,legacy))
                result=probe('restore',restored,prepared)
                self.assertEqual('/b/1',result['target'])
                self.assertTrue(any(k.startswith('dtoon_episode_map_v2:') for k in result['preferences']))
                self.assertEqual(before,list(db.execute('SELECT * FROM chapters')))
                self.assertEqual(offset+2,db.execute('SELECT COUNT(*) FROM works').fetchone()[0])
    def test_settings_not_selected_has_no_persistence_promise(self):
        db=database(7)
        self.assertNotIn('target',probe('restore',works(db),{}))
if __name__=='__main__':unittest.main()
