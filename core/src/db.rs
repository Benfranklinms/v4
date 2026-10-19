//! SQLite store: plays (stats), likes, playlists, saved albums/artists, downloads, local files, settings.

use crate::model::*;
use crate::CoreError;
use rusqlite::{params, Connection, OptionalExtension};
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

pub struct Db {
    pub conn: Mutex<Connection>,
    /// Library revision: bumped after every change to likes, playlists, saved items or downloads.
    rev: std::sync::atomic::AtomicU64,
}

fn e(x: rusqlite::Error) -> CoreError {
    CoreError::Storage { msg: x.to_string() }
}

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS plays(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  track_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, artist_id TEXT,
  album TEXT, thumb TEXT NOT NULL DEFAULT '', duration_s INTEGER NOT NULL DEFAULT 0,
  started_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL, skipped INTEGER NOT NULL DEFAULT 0,
  tz_offset_min INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS plays_started ON plays(started_at);
CREATE INDEX IF NOT EXISTS plays_track ON plays(track_id);
CREATE TABLE IF NOT EXISTS likes(track_id TEXT PRIMARY KEY, json TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS playlists(id TEXT PRIMARY KEY, title TEXT NOT NULL, subtitle TEXT NOT NULL DEFAULT '',
  thumb TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS playlist_tracks(playlist_id TEXT NOT NULL, pos INTEGER NOT NULL, json TEXT NOT NULL,
  PRIMARY KEY(playlist_id, pos));
CREATE TABLE IF NOT EXISTS saved(kind TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL,
  PRIMARY KEY(kind, id));
CREATE TABLE IF NOT EXISTS downloads(track_id TEXT PRIMARY KEY, json TEXT NOT NULL, path TEXT NOT NULL,
  bytes INTEGER NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS local_tracks(track_id TEXT PRIMARY KEY, json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS scrobbles(id INTEGER PRIMARY KEY AUTOINCREMENT, artist TEXT NOT NULL, track TEXT NOT NULL,
  album TEXT NOT NULL DEFAULT '', duration_s INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS mcache(k TEXT PRIMARY KEY, v TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS searches(q TEXT PRIMARY KEY, at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS searches_at ON searches(at);
CREATE INDEX IF NOT EXISTS playlists_created ON playlists(created_at);
CREATE INDEX IF NOT EXISTS likes_created ON likes(created_at);
CREATE INDEX IF NOT EXISTS saved_kind ON saved(kind, created_at);
"#;

/// Lyrics / metadata cache rows expire after 30 days.
pub const MCACHE_TTL_S: i64 = 30 * 24 * 3600;

fn now_s() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

pub struct ArtistAffinity {
    pub plays: HashMap<String, u32>,
    pub skips: HashMap<String, u32>,
    pub track_skips: HashMap<String, u32>,
    pub track_plays: HashMap<String, u32>,
    pub recent: HashSet<String>,
}

impl Db {
    pub fn open(path: &str) -> Result<Self, CoreError> {
        let conn = Connection::open(path).map_err(e)?;
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;").map_err(e)?;
        conn.execute_batch(SCHEMA).map_err(e)?;
        // Keep the DB lean: drop expired cache rows and the old unbounded lyrics rows.
        let _ = conn.execute("DELETE FROM mcache WHERE at < ?1", [now_s() - MCACHE_TTL_S]);
        let _ = conn.execute("DELETE FROM kv WHERE k LIKE 'lyr:%'", []);
        Ok(Self { conn: Mutex::new(conn), rev: std::sync::atomic::AtomicU64::new(1) })
    }

    /// Current library revision (see `rev`).
    pub fn revision(&self) -> u64 {
        self.rev.load(std::sync::atomic::Ordering::SeqCst)
    }
    fn bump(&self) {
        self.rev.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    }

    pub fn kv_get(&self, k: &str) -> Option<String> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.query_row("SELECT v FROM kv WHERE k=?1", [k], |r| r.get(0)).optional().ok().flatten()
    }
    pub fn kv_del(&self, k: &str) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute("DELETE FROM kv WHERE k=?1", [k]).map_err(e)?;
        Ok(())
    }
    pub fn kv_set(&self, k: &str, v: &str) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute("INSERT INTO kv(k,v) VALUES(?1,?2) ON CONFLICT(k) DO UPDATE SET v=excluded.v", params![k, v])
            .map_err(e)?;
        Ok(())
    }

    pub fn mcache_get(&self, k: &str) -> Option<String> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.query_row("SELECT v FROM mcache WHERE k=?1 AND at >= ?2", params![k, now_s() - MCACHE_TTL_S], |r| r.get(0))
            .optional()
            .ok()
            .flatten()
    }
    /// Cached value with the unix time it was written.
    pub fn mcache_get_at(&self, k: &str) -> Option<(String, i64)> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.query_row("SELECT v, at FROM mcache WHERE k=?1", params![k], |r| Ok((r.get(0)?, r.get(1)?))).optional().ok().flatten()
    }
    pub fn mcache_set(&self, k: &str, v: &str) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute(
            "INSERT INTO mcache(k,v,at) VALUES(?1,?2,?3) ON CONFLICT(k) DO UPDATE SET v=excluded.v, at=excluded.at",
            params![k, v, now_s()],
        )
        .map_err(e)?;
        Ok(())
    }

    // ---------------- plays
    pub fn record_play(
        &self,
        t: &Track,
        started_at: i64,
        listened_ms: u64,
        skipped: bool,
        tz_offset_min: i32,
        source: &str,
    ) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute(
            "INSERT INTO plays(track_id,title,artist,artist_id,album,thumb,duration_s,started_at,listened_ms,skipped,tz_offset_min,source)
             VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)",
            params![t.id, t.title, t.artists, t.artist_id, t.album, t.thumb, t.duration_s, started_at, listened_ms as i64,
                skipped as i32, tz_offset_min, source],
        )
        .map_err(e)?;
        Ok(())
    }

    pub fn recent_tracks(&self, limit: u32) -> Result<Vec<Track>, CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c
            .prepare(
                "SELECT track_id,title,artist,artist_id,album,thumb,duration_s,MAX(started_at) m FROM plays
                 GROUP BY track_id ORDER BY m DESC LIMIT ?1",
            )
            .map_err(e)?;
        let rows = st
            .query_map([limit], |r| {
                Ok(Track {
                    id: r.get(0)?,
                    title: r.get(1)?,
                    artists: r.get(2)?,
                    artist_id: r.get(3)?,
                    album: r.get(4)?,
                    album_id: None,
                    thumb: r.get(5)?,
                    duration_s: r.get::<_, i64>(6)? as u32,
                })
            })
            .map_err(e)?;
        Ok(rows.filter_map(|x| x.ok()).collect())
    }

    pub fn top_tracks_all(&self, since: i64, limit: u32) -> Result<Vec<(Track, u32, u32)>, CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c
            .prepare(
                "SELECT track_id,title,artist,artist_id,album,thumb,duration_s,COUNT(*) n,SUM(listened_ms)/60000 FROM plays
                 WHERE started_at>=?1 AND skipped=0 GROUP BY track_id ORDER BY n DESC, 9 DESC LIMIT ?2",
            )
            .map_err(e)?;
        let rows = st
            .query_map(params![since, limit], |r| {
                Ok((
                    Track {
                        id: r.get(0)?,
                        title: r.get(1)?,
                        artists: r.get(2)?,
                        artist_id: r.get(3)?,
                        album: r.get(4)?,
                        album_id: None,
                        thumb: r.get(5)?,
                        duration_s: r.get::<_, i64>(6)? as u32,
                    },
                    r.get::<_, i64>(7)? as u32,
                    r.get::<_, i64>(8)? as u32,
                ))
            })
            .map_err(e)?;
        Ok(rows.filter_map(|x| x.ok()).collect())
    }

    pub fn affinity(&self) -> ArtistAffinity {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut a = ArtistAffinity {
            plays: HashMap::new(),
            skips: HashMap::new(),
            track_skips: HashMap::new(),
            track_plays: HashMap::new(),
            recent: HashSet::new(),
        };
        if let Ok(mut st) = c.prepare(
            "SELECT lower(artist), track_id, skipped, listened_ms, duration_s FROM plays ORDER BY started_at DESC LIMIT 5000",
        ) {
            let rows = st.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, i64>(4)?,
                ))
            });
            if let Ok(rows) = rows {
                for (i, row) in rows.filter_map(|x| x.ok()).enumerate() {
                    let (artist, tid, skipped, _ms, _dur) = row;
                    let main = artist.split(&[',', '&'][..]).next().unwrap_or("").trim().to_string();
                    if i < 60 {
                        a.recent.insert(tid.clone());
                    }
                    if skipped != 0 {
                        *a.skips.entry(main).or_default() += 1;
                        *a.track_skips.entry(tid).or_default() += 1;
                    } else {
                        *a.plays.entry(main).or_default() += 1;
                        *a.track_plays.entry(tid).or_default() += 1;
                    }
                }
            }
        }
        a
    }

    /// (started_at, listened_ms, tz_offset_min, artist, artist_id, thumb, title, track_id, album)
    #[allow(clippy::type_complexity)]
    pub fn plays_since(
        &self,
        since: i64,
    ) -> Result<Vec<(i64, i64, i32, String, Option<String>, String, String, String, Option<String>)>, CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c
            .prepare(
                "SELECT started_at,listened_ms,tz_offset_min,artist,artist_id,thumb,title,track_id,album FROM plays
                 WHERE started_at>=?1 ORDER BY started_at",
            )
            .map_err(e)?;
        let rows = st
            .query_map([since], |r| {
                Ok((
                    r.get(0)?,
                    r.get(1)?,
                    r.get(2)?,
                    r.get(3)?,
                    r.get(4)?,
                    r.get(5)?,
                    r.get(6)?,
                    r.get(7)?,
                    r.get(8)?,
                ))
            })
            .map_err(e)?;
        Ok(rows.filter_map(|x| x.ok()).collect())
    }

    pub fn play_days(&self) -> Result<Vec<i64>, CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c
            .prepare(
                "SELECT DISTINCT (started_at + tz_offset_min*60)/86400 d FROM plays WHERE listened_ms>=30000 ORDER BY d",
            )
            .map_err(e)?;
        let rows = st.query_map([], |r| r.get::<_, i64>(0)).map_err(e)?;
        Ok(rows.filter_map(|x| x.ok()).collect())
    }

    // ---------------- likes
    pub fn set_liked(&self, t: &Track, liked: bool) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        if liked {
            c.execute(
                "INSERT OR REPLACE INTO likes(track_id,json,created_at) VALUES(?1,?2,?3)",
                params![t.id, serde_json::to_string(t).unwrap_or_default(), crate::yt::now()],
            )
            .map_err(e)?;
        } else {
            c.execute("DELETE FROM likes WHERE track_id=?1", [&t.id]).map_err(e)?;
        }
        self.bump();
        Ok(())
    }
    pub fn is_liked(&self, id: &str) -> bool {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.query_row("SELECT 1 FROM likes WHERE track_id=?1", [id], |_| Ok(())).optional().ok().flatten().is_some()
    }
    pub fn liked(&self) -> Result<Vec<Track>, CoreError> {
        self.json_list("SELECT json FROM likes ORDER BY created_at DESC", [])
    }

    fn json_list<T: serde::de::DeserializeOwned, P: rusqlite::Params>(&self, sql: &str, p: P) -> Result<Vec<T>, CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c.prepare(sql).map_err(e)?;
        let rows = st.query_map(p, |r| r.get::<_, String>(0)).map_err(e)?;
        Ok(rows.filter_map(|x| x.ok()).filter_map(|j| serde_json::from_str(&j).ok()).collect())
    }

    // ---------------- playlists
    pub fn create_playlist(&self, id: &str, title: &str, subtitle: &str, thumb: &str, source: &str, tracks: &[Track]) -> Result<(), CoreError> {
        let mut c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let tx = c.transaction().map_err(e)?;
        tx.execute(
            "INSERT OR REPLACE INTO playlists(id,title,subtitle,thumb,source,created_at) VALUES(?1,?2,?3,?4,?5,?6)",
            params![id, title, subtitle, thumb, source, crate::yt::now()],
        )
        .map_err(e)?;
        tx.execute("DELETE FROM playlist_tracks WHERE playlist_id=?1", [id]).map_err(e)?;
        for (i, t) in tracks.iter().enumerate() {
            tx.execute(
                "INSERT INTO playlist_tracks(playlist_id,pos,json) VALUES(?1,?2,?3)",
                params![id, i as i64, serde_json::to_string(t).unwrap_or_default()],
            )
            .map_err(e)?;
        }
        tx.commit().map_err(e)?;
        self.bump();
        Ok(())
    }
    pub fn add_to_playlist(&self, id: &str, t: &Track) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let n: i64 = c
            .query_row("SELECT COALESCE(MAX(pos)+1,0) FROM playlist_tracks WHERE playlist_id=?1", [id], |r| r.get(0))
            .map_err(e)?;
        c.execute(
            "INSERT INTO playlist_tracks(playlist_id,pos,json) VALUES(?1,?2,?3)",
            params![id, n, serde_json::to_string(t).unwrap_or_default()],
        )
        .map_err(e)?;
        self.bump();
        Ok(())
    }
    /// Rename / edit details of a library playlist (title, description, optional cover url).
    pub fn edit_playlist(&self, id: &str, title: &str, subtitle: &str, thumb: Option<&str>) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        match thumb {
            Some(t) => c.execute("UPDATE playlists SET title=?2, subtitle=?3, thumb=?4 WHERE id=?1", params![id, title, subtitle, t]),
            None => c.execute("UPDATE playlists SET title=?2, subtitle=?3 WHERE id=?1", params![id, title, subtitle]),
        }
        .map_err(e)?;
        self.bump();
        Ok(())
    }
    /// Delete many playlists in ONE transaction (bulk delete from multi-select stays a single fsync).
    pub fn delete_playlists(&self, ids: &[String]) -> Result<u32, CoreError> {
        let mut c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let tx = c.transaction().map_err(e)?;
        let mut n = 0u32;
        for id in ids {
            n += tx.execute("DELETE FROM playlists WHERE id=?1", [id]).map_err(e)? as u32;
            tx.execute("DELETE FROM playlist_tracks WHERE playlist_id=?1", [id]).map_err(e)?;
        }
        tx.commit().map_err(e)?;
        self.bump();
        Ok(n)
    }
    /// Remove the track at `pos` and close the gap (one transaction).
    pub fn remove_from_playlist(&self, id: &str, pos: i64) -> Result<(), CoreError> {
        let mut c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let tx = c.transaction().map_err(e)?;
        let rows: Vec<String> = {
            let mut st = tx.prepare("SELECT json FROM playlist_tracks WHERE playlist_id=?1 ORDER BY pos").map_err(e)?;
            let r = st.query_map([id], |r| r.get::<_, String>(0)).map_err(e)?.filter_map(|x| x.ok()).collect();
            r
        };
        tx.execute("DELETE FROM playlist_tracks WHERE playlist_id=?1", [id]).map_err(e)?;
        let mut i = 0i64;
        for (k, j) in rows.iter().enumerate() {
            if k as i64 == pos {
                continue;
            }
            tx.execute("INSERT INTO playlist_tracks(playlist_id,pos,json) VALUES(?1,?2,?3)", params![id, i, j]).map_err(e)?;
            i += 1;
        }
        tx.commit().map_err(e)?;
        self.bump();
        Ok(())
    }
    pub fn add_search(&self, q: &str) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute("INSERT OR REPLACE INTO searches(q,at) VALUES(?1,?2)", params![q, now_s()]).map_err(e)?;
        c.execute("DELETE FROM searches WHERE q NOT IN (SELECT q FROM searches ORDER BY at DESC LIMIT 30)", []).map_err(e)?;
        Ok(())
    }
    pub fn searches(&self, limit: u32) -> Vec<String> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let Ok(mut st) = c.prepare("SELECT q FROM searches ORDER BY at DESC LIMIT ?1") else { return vec![] };
        let r = st.query_map([limit], |r| r.get::<_, String>(0)).map(|it| it.filter_map(|x| x.ok()).collect()).unwrap_or_default();
        r
    }
    pub fn remove_search(&self, q: Option<&str>) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        match q {
            Some(q) => c.execute("DELETE FROM searches WHERE q=?1", [q]),
            None => c.execute("DELETE FROM searches", []),
        }
        .map_err(e)?;
        Ok(())
    }
    pub fn delete_playlist(&self, id: &str) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute("DELETE FROM playlists WHERE id=?1", [id]).map_err(e)?;
        c.execute("DELETE FROM playlist_tracks WHERE playlist_id=?1", [id]).map_err(e)?;
        self.bump();
        Ok(())
    }
    pub fn playlists(&self) -> Result<Vec<Playlist>, CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c
            .prepare(
                "SELECT p.id,p.title,p.subtitle,p.thumb,p.source,(SELECT COUNT(*) FROM playlist_tracks t WHERE t.playlist_id=p.id)
                 FROM playlists p ORDER BY p.created_at DESC",
            )
            .map_err(e)?;
        let rows = st
            .query_map([], |r| {
                Ok(Playlist {
                    id: r.get(0)?,
                    title: r.get(1)?,
                    subtitle: r.get(2)?,
                    thumb: r.get(3)?,
                    source: r.get(4)?,
                    track_count: Some(r.get::<_, i64>(5)? as u64),
                })
            })
            .map_err(e)?;
        Ok(rows.filter_map(|x| x.ok()).collect())
    }
    pub fn playlist_tracks(&self, id: &str) -> Result<Vec<Track>, CoreError> {
        self.json_list("SELECT json FROM playlist_tracks WHERE playlist_id=?1 ORDER BY pos", [id])
    }

    // ---------------- saved albums / artists
    pub fn set_saved(&self, kind: &str, id: &str, json: Option<String>) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        match json {
            Some(j) => c
                .execute(
                    "INSERT OR REPLACE INTO saved(kind,id,json,created_at) VALUES(?1,?2,?3,?4)",
                    params![kind, id, j, crate::yt::now()],
                )
                .map_err(e)?,
            None => c.execute("DELETE FROM saved WHERE kind=?1 AND id=?2", [kind, id]).map_err(e)?,
        };
        self.bump();
        Ok(())
    }
    pub fn is_saved(&self, kind: &str, id: &str) -> bool {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.query_row("SELECT 1 FROM saved WHERE kind=?1 AND id=?2", [kind, id], |_| Ok(()))
            .optional()
            .ok()
            .flatten()
            .is_some()
    }
    pub fn saved<T: serde::de::DeserializeOwned>(&self, kind: &str) -> Result<Vec<T>, CoreError> {
        self.json_list("SELECT json FROM saved WHERE kind=?1 ORDER BY created_at DESC", [kind])
    }

    // ---------------- downloads
    pub fn add_download(&self, t: &Track, path: &str, bytes: u64) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute(
            "INSERT OR REPLACE INTO downloads(track_id,json,path,bytes,created_at) VALUES(?1,?2,?3,?4,?5)",
            params![t.id, serde_json::to_string(t).unwrap_or_default(), path, bytes as i64, crate::yt::now()],
        )
        .map_err(e)?;
        self.bump();
        Ok(())
    }
    pub fn download_path(&self, id: &str) -> Option<String> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.query_row("SELECT path FROM downloads WHERE track_id=?1", [id], |r| r.get(0)).optional().ok().flatten()
    }
    pub fn remove_download(&self, id: &str) -> Result<Option<String>, CoreError> {
        let p = self.download_path(id);
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        c.execute("DELETE FROM downloads WHERE track_id=?1", [id]).map_err(e)?;
        self.bump();
        Ok(p)
    }
    pub fn downloads(&self) -> Result<Vec<DownloadItem>, CoreError> {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c
            .prepare("SELECT json,path,bytes,created_at FROM downloads ORDER BY created_at DESC")
            .map_err(e)?;
        let rows = st
            .query_map([], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, i64>(2)?, r.get::<_, i64>(3)?))
            })
            .map_err(e)?;
        Ok(rows
            .filter_map(|x| x.ok())
            .filter_map(|(j, p, b, t)| {
                serde_json::from_str(&j).ok().map(|track| DownloadItem { track, path: p, bytes: b as u64, created_at: t })
            })
            .collect())
    }

    // ---------------- local files
    pub fn set_local_tracks(&self, tracks: &[Track]) -> Result<(), CoreError> {
        let mut c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let tx = c.transaction().map_err(e)?;
        tx.execute("DELETE FROM local_tracks", []).map_err(e)?;
        for t in tracks {
            tx.execute(
                "INSERT OR REPLACE INTO local_tracks(track_id,json) VALUES(?1,?2)",
                params![t.id, serde_json::to_string(t).unwrap_or_default()],
            )
            .map_err(e)?;
        }
        tx.commit().map_err(e)?;
        self.bump();
        Ok(())
    }
    pub fn local_tracks(&self) -> Result<Vec<Track>, CoreError> {
        self.json_list("SELECT json FROM local_tracks", [])
    }

    pub fn counts(&self) -> LibraryCounts {
        let c = self.conn.lock().unwrap_or_else(|p| p.into_inner());
        let n = |sql: &str| -> u32 { c.query_row(sql, [], |r| r.get::<_, i64>(0)).unwrap_or(0) as u32 };
        LibraryCounts {
            liked: n("SELECT COUNT(*) FROM likes"),
            playlists: n("SELECT COUNT(*) FROM playlists"),
            albums: n("SELECT COUNT(*) FROM saved WHERE kind='album'"),
            artists: n("SELECT COUNT(*) FROM saved WHERE kind='artist'"),
            downloads: n("SELECT COUNT(*) FROM downloads"),
            local: n("SELECT COUNT(*) FROM local_tracks"),
        }
    }
}
