//! Optional Last.fm scrobbling. Keys come from the app build (CI secrets); empty keys = feature off.
//! Web auth: auth.getToken -> user approves in the browser -> auth.getSession. Scrobbles are queued
//! in the DB (offline-safe) and flushed in batches of up to 50.

use crate::db::Db;
use crate::model::Track;
use crate::CoreError;
use md5::{Digest, Md5};
use std::collections::BTreeMap;

const API: &str = "https://ws.audioscrobbler.com/2.0/";

pub struct Keys {
    pub key: String,
    pub secret: String,
}

fn sign(params: &BTreeMap<String, String>, secret: &str) -> String {
    let mut s = String::new();
    for (k, v) in params {
        if k == "format" || k == "callback" {
            continue;
        }
        s.push_str(k);
        s.push_str(v);
    }
    s.push_str(secret);
    let mut h = Md5::new();
    h.update(s.as_bytes());
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

fn net(e: reqwest::Error) -> CoreError {
    CoreError::Network { msg: e.to_string() }
}

pub async fn call(http: &reqwest::Client, k: &Keys, method: &str, mut p: BTreeMap<String, String>, post: bool) -> Result<serde_json::Value, CoreError> {
    p.insert("method".into(), method.into());
    p.insert("api_key".into(), k.key.clone());
    let sig = sign(&p, &k.secret);
    p.insert("api_sig".into(), sig);
    p.insert("format".into(), "json".into());
    let req = if post { http.post(API).form(&p) } else { http.get(API).query(&p) };
    let v: serde_json::Value = req.send().await.map_err(net)?.json().await.map_err(net)?;
    if let Some(code) = v.get("error").and_then(|e| e.as_i64()) {
        let msg = v.get("message").and_then(|m| m.as_str()).unwrap_or("error").to_string();
        return Err(if code == 9 || code == 4 || code == 14 || code == 15 {
            CoreError::Auth { status: code as u16 }
        } else {
            CoreError::Network { msg: format!("last.fm {code}: {msg}") }
        });
    }
    Ok(v)
}

pub async fn get_token(http: &reqwest::Client, k: &Keys) -> Result<(String, String), CoreError> {
    let v = call(http, k, "auth.getToken", BTreeMap::new(), false).await?;
    let t = v.get("token").and_then(|t| t.as_str()).ok_or(CoreError::Invalid { msg: "no token".into() })?.to_string();
    let url = format!("https://www.last.fm/api/auth/?api_key={}&token={}", k.key, t);
    Ok((t, url))
}

pub async fn get_session(http: &reqwest::Client, k: &Keys, token: &str) -> Result<(String, String), CoreError> {
    let mut p = BTreeMap::new();
    p.insert("token".into(), token.to_string());
    let v = call(http, k, "auth.getSession", p, false).await?;
    let s = v.get("session").ok_or(CoreError::Invalid { msg: "not approved yet".into() })?;
    let key = s.get("key").and_then(|x| x.as_str()).unwrap_or_default().to_string();
    let name = s.get("name").and_then(|x| x.as_str()).unwrap_or_default().to_string();
    if key.is_empty() {
        return Err(CoreError::Invalid { msg: "not approved yet".into() });
    }
    Ok((key, name))
}

fn primary_artist(t: &Track) -> String {
    t.artists.split([',', '&']).next().unwrap_or(&t.artists).trim().to_string()
}

pub async fn now_playing(http: &reqwest::Client, k: &Keys, sk: &str, t: &Track) -> Result<(), CoreError> {
    let mut p = BTreeMap::new();
    p.insert("sk".into(), sk.to_string());
    p.insert("artist".into(), primary_artist(t));
    p.insert("track".into(), t.title.clone());
    if let Some(a) = &t.album {
        p.insert("album".into(), a.clone());
    }
    if t.duration_s > 0 {
        p.insert("duration".into(), t.duration_s.to_string());
    }
    call(http, k, "track.updateNowPlaying", p, true).await.map(|_| ())
}

/// Scrobble rule: track longer than 30 s, listened to at least half of it or 4 minutes.
pub fn qualifies(duration_s: u32, listened_ms: u64) -> bool {
    let d = duration_s as u64 * 1000;
    (duration_s == 0 || duration_s > 30) && (listened_ms >= 240_000 || (d > 0 && listened_ms * 2 >= d))
}

pub fn enqueue(db: &Db, t: &Track, started_at: i64) -> Result<(), CoreError> {
    let c = db.conn.lock().unwrap_or_else(|p| p.into_inner());
    c.execute(
        "INSERT INTO scrobbles(artist,track,album,duration_s,ts) VALUES(?1,?2,?3,?4,?5)",
        rusqlite::params![primary_artist(t), t.title, t.album.clone().unwrap_or_default(), t.duration_s, started_at],
    )
    .map_err(|e| CoreError::Storage { msg: e.to_string() })?;
    Ok(())
}

pub fn pending(db: &Db) -> u32 {
    let c = db.conn.lock().unwrap_or_else(|p| p.into_inner());
    c.query_row("SELECT COUNT(*) FROM scrobbles", [], |r| r.get::<_, u32>(0)).unwrap_or(0)
}

/// Sends up to 50 queued scrobbles; returns how many were accepted.
pub async fn flush(http: &reqwest::Client, k: &Keys, sk: &str, db: &Db) -> Result<u32, CoreError> {
    let rows: Vec<(i64, String, String, String, u32, i64)> = {
        let c = db.conn.lock().unwrap_or_else(|p| p.into_inner());
        let mut st = c
            .prepare("SELECT id,artist,track,album,duration_s,ts FROM scrobbles ORDER BY ts LIMIT 50")
            .map_err(|e| CoreError::Storage { msg: e.to_string() })?;
        let it = st
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?)))
            .map_err(|e| CoreError::Storage { msg: e.to_string() })?;
        it.filter_map(|x| x.ok()).collect()
    };
    if rows.is_empty() {
        return Ok(0);
    }
    let mut p = BTreeMap::new();
    p.insert("sk".into(), sk.to_string());
    for (i, (_, artist, track, album, dur, ts)) in rows.iter().enumerate() {
        p.insert(format!("artist[{i}]"), artist.clone());
        p.insert(format!("track[{i}]"), track.clone());
        p.insert(format!("timestamp[{i}]"), ts.to_string());
        if !album.is_empty() {
            p.insert(format!("album[{i}]"), album.clone());
        }
        if *dur > 0 {
            p.insert(format!("duration[{i}]"), dur.to_string());
        }
    }
    call(http, k, "track.scrobble", p, true).await?;
    let max_id = rows.iter().map(|r| r.0).max().unwrap_or(0);
    let c = db.conn.lock().unwrap_or_else(|p| p.into_inner());
    let _ = c.execute("DELETE FROM scrobbles WHERE id <= ?1", [max_id]);
    Ok(rows.len() as u32)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rule() {
        assert!(!qualifies(20, 20_000));
        assert!(qualifies(200, 100_000));
        assert!(!qualifies(200, 99_000));
        assert!(qualifies(900, 240_000));
    }
    #[test]
    fn signature() {
        let mut p = BTreeMap::new();
        p.insert("method".to_string(), "auth.getToken".to_string());
        p.insert("api_key".to_string(), "k".to_string());
        p.insert("format".to_string(), "json".to_string());
        assert_eq!(sign(&p, "s").len(), 32);
    }
}
