//! itune-core: the shared engine behind the Android and macOS apps (exposed with UniFFI).

pub mod cache;
pub mod db;
pub mod import;
pub mod isa;
pub mod lastfm;
pub mod lyrics;
pub mod model;
pub mod queue;
pub mod stats;
pub mod update;
pub mod yt;

use db::Db;
use model::*;
use queue::Queue;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use yt::{now, Yt};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("network: {msg}")]
    Network { msg: String },
    #[error("unavailable: {msg}")]
    Unavailable { msg: String },
    #[error("storage: {msg}")]
    Storage { msg: String },
    #[error("{msg}")]
    Invalid { msg: String },
    /// 401/404 from the releases API (repo private / token missing or wrong).
    #[error("auth: HTTP {status}")]
    Auth { status: u16 },
}

#[derive(uniffi::Object)]
pub struct Core {
    data_dir: String,
    prefer_mp4: bool,
    yt: Arc<Yt>,
    db: Arc<Db>,
    queue: Mutex<Queue>,
    streams: Mutex<HashMap<String, StreamInfo>>,
    progress: Arc<Mutex<HashMap<String, f32>>>,
    lastfm: Mutex<Option<lastfm::Keys>>,
    import: Arc<import::Progress>,
    /// Last queue JSON written to the DB (queue rows are only rewritten when the queue changes).
    session_sig: Mutex<String>,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct LastfmState {
    /// Keys were provided at build time (feature visible).
    pub available: bool,
    pub enabled: bool,
    pub username: String,
    pub pending: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LastfmAuth {
    pub token: String,
    pub url: String,
}

impl Core {
    /// Spotify-style "similar songs" for Up next / radio: YouTube Music watch-next automix (official tracks first),
    /// falling back to rustypipe's radio; other versions of the seed, non-originals, already queued and recently
    /// played tracks are dropped, then the taste engine reranks (no artist twice in a row).
    async fn similar(&self, seed_id: &str, seed: Option<&Track>, exclude: &HashSet<String>) -> Result<Vec<Track>, CoreError> {
        let mut cands: Vec<(Track, u8)> = match self.yt.watch_next(seed_id).await {
            Ok(items) => items
                .into_iter()
                .filter(|i| !i.kind.contains("PODCAST") && !i.kind.contains("EPISODE"))
                .map(|i| {
                    let rank = match i.kind.as_str() {
                        "ATV" => 0,
                        "OMV" | "OFFICIAL_SOURCE_MUSIC" => 1,
                        _ => 2,
                    };
                    (i.track, rank)
                })
                .collect(),
            Err(_) => self.yt.radio(seed_id).await?.into_iter().map(|t| (t, 1)).collect(),
        };
        let seed_t = seed.cloned().or_else(|| cands.iter().find(|c| c.0.id == seed_id).map(|c| c.0.clone()));
        let recent: HashSet<String> = self.db.recent_tracks(40).unwrap_or_default().into_iter().map(|t| t.id).collect();
        let mut seen_titles = HashSet::new();
        cands.retain(|(t, _)| {
            if t.id == seed_id || exclude.contains(&t.id) || recent.contains(&t.id) {
                return false;
            }
            if let Some(s) = &seed_t {
                if yt::is_other_version_of(s, t) {
                    return false;
                }
            }
            if yt::looks_like_non_original(t) {
                return false;
            }
            seen_titles.insert(format!("{}|{}", import::norm(&t.title), t.main_artist().to_lowercase()))
        });
        // user uploads only when there aren't enough official tracks
        if cands.iter().filter(|c| c.1 < 2).count() >= 10 {
            cands.retain(|c| c.1 < 2);
        }
        cands.sort_by_key(|c| c.1);
        let aff = self.db.affinity();
        Ok(queue::rerank(cands.into_iter().map(|c| c.0).collect(), &aff, exclude))
    }
}

/// FFI panic guard for infallible exports: a panic returns the type's default instead of aborting the app
/// (UniFFI turns panics in `Result` exports into errors on its own).
const MIX_TTL_S: i64 = 6 * 3600;
const ALBUM_TTL_S: i64 = 24 * 3600;

impl Core {
    /// Cached page younger than `ttl` seconds.
    fn page_cache<T: serde::de::DeserializeOwned>(&self, key: &str, ttl: i64) -> Option<T> {
        let (v, at) = self.db.mcache_get_at(key)?;
        if now() - at > ttl {
            return None;
        }
        serde_json::from_str(&v).ok()
    }
    fn page_store<T: serde::Serialize>(&self, key: &str, v: &T) {
        if let Ok(j) = serde_json::to_string(v) {
            let _ = self.db.mcache_set(key, &j);
        }
    }
}

/// Cold-launch fast path: the last saved Home feed read straight from the DB file (read-only, no network,
/// no `Core` construction), so the UI can paint shelves before the core is even up. None if nothing saved.
/// `data_dir` is the same folder passed to `Core::new`.
#[uniffi::export]
pub fn home_cached_peek(data_dir: String, tz_offset_min: i32) -> Option<HomeFeed> {
    guard(move || {
        let conn = rusqlite::Connection::open_with_flags(
            format!("{data_dir}/itune.db"),
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .ok()?;
        let j: String = conn.query_row("SELECT v FROM kv WHERE k='home_feed'", [], |r| r.get(0)).ok()?;
        let mut f: HomeFeed = serde_json::from_str(&j).ok()?;
        let local = now() + tz_offset_min as i64 * 60;
        f.greeting = greeting_for((local.rem_euclid(86_400)) / 3600);
        Some(f)
    })
}

fn greeting_for(hour: i64) -> String {
    match hour {
        5..=11 => "Good morning",
        12..=16 => "Good afternoon",
        17..=21 => "Good evening",
        _ => "Late night",
    }
    .to_string()
}

fn guard<T: Default>(f: impl FnOnce() -> T) -> T {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_default()
}

fn io(e: std::io::Error) -> CoreError {
    CoreError::Storage { msg: e.to_string() }
}

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    /// `platform`: "android" or "macos". macOS gets AAC/m4a streams (AVPlayer), Android may use Opus.
    #[uniffi::constructor]
    pub fn new(data_dir: String, platform: String) -> Result<Arc<Self>, CoreError> {
        std::fs::create_dir_all(&data_dir).map_err(io)?;
        std::fs::create_dir_all(format!("{data_dir}/downloads")).map_err(io)?;
        let db = Db::open(&format!("{data_dir}/itune.db"))?;
        let yt = Yt::new(&data_dir)?;
        Ok(Arc::new(Self {
            data_dir,
            prefer_mp4: platform == "macos",
            yt: Arc::new(yt),
            db: Arc::new(db),
            queue: Mutex::new(Queue::new()),
            streams: Mutex::new(HashMap::new()),
            progress: Arc::new(Mutex::new(HashMap::new())),
            lastfm: Mutex::new(None),
            import: Arc::new(import::Progress::default()),
            session_sig: Mutex::new(String::new()),
        }))
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_string()
    }

    // ------------------------------------------------------------------ catalogue
    pub async fn search(&self, query: String) -> Result<SearchResults, CoreError> {
        self.yt.search(&query).await
    }
    pub async fn search_suggestions(&self, query: String) -> Result<Vec<String>, CoreError> {
        self.yt.suggestions(&query).await
    }
    /// Album page: served from the page cache when younger than 24 h, otherwise fetched (and cached).
    pub async fn album(&self, id: String) -> Result<AlbumPage, CoreError> {
        if let Some(p) = self.page_cache::<AlbumPage>(&format!("pg:al:{id}"), ALBUM_TTL_S) {
            return Ok(p);
        }
        let p = self.yt.album(&id).await?;
        self.page_store(&format!("pg:al:{id}"), &p);
        Ok(p)
    }
    /// Artist page: cached 24 h like albums.
    pub async fn artist(&self, id: String) -> Result<ArtistPage, CoreError> {
        if let Some(p) = self.page_cache::<ArtistPage>(&format!("pg:ar:{id}"), ALBUM_TTL_S) {
            return Ok(p);
        }
        let p = self.yt.artist(&id).await?;
        self.page_store(&format!("pg:ar:{id}"), &p);
        Ok(p)
    }
    /// Last cached album page at any age (instant, no network); then call `album` to revalidate.
    pub fn album_cached(&self, id: String) -> Option<AlbumPage> {
        guard(move || self.page_cache::<AlbumPage>(&format!("pg:al:{id}"), i64::MAX))
    }
    pub fn artist_cached(&self, id: String) -> Option<ArtistPage> {
        guard(move || self.page_cache::<ArtistPage>(&format!("pg:ar:{id}"), i64::MAX))
    }
    /// Last cached remote playlist / mix page at any age (local playlists: read straight from the DB).
    pub fn playlist_cached(&self, id: String) -> Option<PlaylistPage> {
        guard(move || {
            if id.starts_with("local:") || id.starts_with("import:") || id.starts_with("isa:") || id == "liked" {
                return None;
            }
            self.page_cache::<PlaylistPage>(&format!("pg:pl:{id}"), i64::MAX)
        })
    }
    /// Next ~`count` tracks of a remote playlist opened with `playlist` (first page comes back at once).
    /// Append them in the UI and to the queue; call again until `done`.
    pub async fn playlist_more(&self, id: String, count: u32) -> Result<PlaylistMore, CoreError> {
        let (tracks, done) = self.yt.playlist_more(&id, count as usize).await?;
        let key = format!("pg:pl:{id}");
        if done {
            let _ = self.db.kv_set(&format!("{key}:done"), "1");
        }
        if !tracks.is_empty() {
            if let Some(mut p) = self.page_cache::<PlaylistPage>(&key, i64::MAX) {
                for t in &tracks {
                    if !p.tracks.iter().any(|x| x.id == t.id) {
                        p.tracks.push(t.clone());
                    }
                }
                self.page_store(&key, &p);
            }
        }
        Ok(PlaylistMore { tracks, done })
    }
    /// More pages are available for a remote playlist opened in this session.
    pub fn playlist_has_more(&self, id: String) -> bool {
        guard(move || self.yt.playlist_has_more(&id))
    }
    pub async fn playlist(&self, id: String) -> Result<PlaylistPage, CoreError> {
        if id.starts_with("local:") || id.starts_with("import:") || id.starts_with("isa:") {
            let pl = self.db.playlists()?.into_iter().find(|p| p.id == id).ok_or(CoreError::Invalid { msg: "No such playlist".into() })?;
            return Ok(PlaylistPage { tracks: self.db.playlist_tracks(&id)?, playlist: pl });
        }
        if id == "liked" {
            let t = self.db.liked()?;
            return Ok(PlaylistPage {
                playlist: Playlist {
                    id,
                    title: "Liked Songs".into(),
                    subtitle: format!("{} songs", t.len()),
                    thumb: t.first().map(|x| x.thumb.clone()).unwrap_or_default(),
                    track_count: Some(t.len() as u64),
                    source: "local".into(),
                },
                tracks: t,
            });
        }
        // Remote playlist / mix: a complete cached copy younger than 6 h is served as is.
        let key = format!("pg:pl:{id}");
        let complete = self.db.kv_get(&format!("{key}:done")).is_some();
        if complete {
            if let Some(p) = self.page_cache::<PlaylistPage>(&key, MIX_TTL_S) {
                return Ok(p);
            }
        }
        let mut p = self.yt.playlist(&id).await?;
        let more = self.yt.playlist_has_more(&id);
        // keep the longer list fetched earlier (later pages arrive via playlist_more and are de-duplicated)
        if let Some(c) = self.page_cache::<PlaylistPage>(&key, i64::MAX) {
            if c.tracks.len() > p.tracks.len() && c.tracks.first().map(|t| &t.id) == p.tracks.first().map(|t| &t.id) {
                p.tracks = c.tracks;
            }
        }
        self.page_store(&key, &p);
        if more {
            let _ = self.db.kv_del(&format!("{key}:done"));
        } else {
            let _ = self.db.kv_set(&format!("{key}:done"), "1");
        }
        Ok(p)
    }
    pub async fn radio(&self, video_id: String) -> Result<Vec<Track>, CoreError> {
        let seed = self.queue.lock().unwrap_or_else(|p| p.into_inner()).tracks.iter().find(|t| t.id == video_id).cloned();
        let mut ex = HashSet::new();
        ex.insert(video_id.clone());
        let mut r = self.similar(&video_id, seed.as_ref(), &ex).await?;
        r.truncate(40);
        Ok(r)
    }

    /// Stale-while-revalidate: the last full feed `home()` built, instantly from the local DB (no network),
    /// with the greeting and Recently played refreshed. None before the first successful `home()`.
    /// Render this at launch, then call `home()` in the background and swap the result in.
    pub fn home_cached(&self, tz_offset_min: i32) -> Option<HomeFeed> {
        guard(move || {
            let mut f: HomeFeed = serde_json::from_str(&self.db.kv_get("home_feed")?).ok()?;
            let local = now() + tz_offset_min as i64 * 60;
            f.greeting = greeting_for((local.rem_euclid(86_400)) / 3600);
            if let Ok(r) = self.db.recent_tracks(12) {
                f.recent = r;
            }
            Some(f)
        })
    }

    pub async fn home(&self, tz_offset_min: i32) -> Result<HomeFeed, CoreError> {
        let local = now() + tz_offset_min as i64 * 60;
        let hour = (local.rem_euclid(86_400)) / 3600;
        let greeting = greeting_for(hour);
        let recent = self.db.recent_tracks(12)?;
        // Seeds: the listener's most played tracks by distinct artist; charts for a new listener.
        let mut seeds: Vec<Track> = Vec::new();
        let mut seen_artists = HashSet::new();
        for (t, _, _) in self.db.top_tracks_all(now() - 30 * 86_400, 60)? {
            if t.is_local() {
                continue;
            }
            if seen_artists.insert(t.main_artist().to_lowercase()) {
                seeds.push(t);
            }
            if seeds.len() >= 6 {
                break;
            }
        }
        // YouTube Music home shelves and charts in parallel; either may fail without failing the feed.
        let cookie = self.db.kv_get("auth_cookie");
        let (shelves, trending) = tokio::join!(
            tokio::time::timeout(std::time::Duration::from_secs(15), self.yt.home_shelves(2, cookie.as_deref())),
            tokio::time::timeout(std::time::Duration::from_secs(15), self.yt.charts())
        );
        let mut shelves = shelves.ok().and_then(|r| r.ok()).unwrap_or_default();
        // Never an empty Home: keep the last good shelves and serve them when YouTube Music can't be reached.
        if shelves.is_empty() {
            shelves = self.db.kv_get("home_shelves").and_then(|j| serde_json::from_str(&j).ok()).unwrap_or_default();
        } else if let Ok(j) = serde_json::to_string(&shelves) {
            let _ = self.db.kv_set("home_shelves", &j);
        }
        let mut trending = trending.ok().and_then(|r| r.ok()).unwrap_or_default();
        if trending.is_empty() {
            // charts unavailable: use the songs of the home shelves as the trending/seed pool
            for sh in &shelves {
                for it in &sh.items {
                    if let Some(t) = &it.track {
                        if !trending.iter().any(|x: &Track| x.id == t.id) {
                            trending.push(t.clone());
                        }
                    }
                }
            }
        }
        for t in trending.iter() {
            if seeds.len() >= 6 {
                break;
            }
            if seen_artists.insert(t.main_artist().to_lowercase()) {
                seeds.push(t.clone());
            }
        }
        let day = local.div_euclid(86_400);
        let cache_key = format!("mixes:{day}:{}", seeds.iter().map(|s| s.id.as_str()).collect::<Vec<_>>().join(","));
        let mixes: Vec<Mix> = if let Some(j) = self.db.kv_get("mixes_cache").filter(|j| j.starts_with(&cache_key)) {
            serde_json::from_str::<Vec<MixJson>>(&j[cache_key.len()..]).map(|v| v.into_iter().map(Into::into).collect()).unwrap_or_default()
        } else {
            let mut set = tokio::task::JoinSet::new();
            for (i, s) in seeds.iter().enumerate() {
                let yt = self.yt.clone();
                let s = s.clone();
                set.spawn(async move { (i, s.clone(), tokio::time::timeout(std::time::Duration::from_secs(12), yt.radio(&s.id)).await) });
            }
            let aff = self.db.affinity();
            let mut out: Vec<(usize, Mix)> = Vec::new();
            while let Some(Ok((i, seed, r))) = set.join_next().await {
                let Ok(Ok(c)) = r else { continue };
                let mut ex = HashSet::new();
                ex.insert(seed.id.clone());
                let mut tracks = vec![seed.clone()];
                tracks.extend(queue::rerank(c, &aff, &ex));
                tracks.truncate(40);
                let mut names: Vec<String> = Vec::new();
                for t in &tracks {
                    let a = t.main_artist();
                    if !a.is_empty() && !names.contains(&a) {
                        names.push(a);
                    }
                    if names.len() >= 4 {
                        break;
                    }
                }
                out.push((i, Mix {
                    id: format!("mix:{}", seed.id),
                    title: String::new(),
                    subtitle: names.join(", "),
                    thumb: seed.thumb.clone(),
                    seed_track_id: Some(seed.id.clone()),
                    tracks,
                }));
            }
            out.sort_by_key(|x| x.0);
            let mixes: Vec<Mix> = out
                .into_iter()
                .enumerate()
                .map(|(n, (_, mut m))| {
                    m.title = format!("Daily Mix {}", n + 1);
                    m
                })
                .collect();
            if !mixes.is_empty() {
                let j: Vec<MixJson> = mixes.iter().cloned().map(Into::into).collect();
                let _ = self.db.kv_set("mixes_cache", &format!("{cache_key}{}", serde_json::to_string(&j).unwrap_or_default()));
            }
            mixes
        };
        let suggestions = suggestions_for(hour);
        let on_repeat: Vec<Track> = self.db.top_tracks_all(now() - 30 * 86_400, 25)?.into_iter().filter(|(_, plays, _)| *plays >= 2).map(|(t, _, _)| t).collect();
        // Discover: tracks from the mixes the listener has never played, ranked by the taste engine.
        let aff = self.db.affinity();
        let mut discover: Vec<Track> = Vec::new();
        let mut seen = HashSet::new();
        for m in &mixes {
            for t in m.tracks.iter().skip(1) {
                if !aff.track_plays.contains_key(&t.id) && seen.insert(t.id.clone()) {
                    discover.push(t.clone());
                }
            }
        }
        let mut discover = queue::rerank(discover, &aff, &HashSet::new());
        discover.truncate(30);
        if mixes.is_empty() && shelves.is_empty() && trending.is_empty() && recent.is_empty() {
            return Err(CoreError::Network { msg: "Couldn't load the home feed".into() });
        }
        let feed = HomeFeed { greeting, mixes, recent, trending: trending.into_iter().take(20).collect(), suggestions, shelves, on_repeat, discover };
        if !feed.shelves.is_empty() || !feed.mixes.is_empty() {
            if let Ok(j) = serde_json::to_string(&feed) {
                let _ = self.db.kv_set("home_feed", &j);
            }
        }
        Ok(feed)
    }

    // ------------------------------------------------------------------ streams
    /// Resolve a stream URL (cached until shortly before it expires).
    pub async fn stream(&self, id: String) -> Result<StreamInfo, CoreError> {
        if let Some(s) = self.streams.lock().unwrap_or_else(|p| p.into_inner()).get(&id).cloned() {
            if s.expires_at - now() > 600 {
                return Ok(s);
            }
        }
        let s = self.yt.stream(&id, self.prefer_mp4, None).await?;
        self.streams.lock().unwrap_or_else(|p| p.into_inner()).insert(id, s.clone());
        Ok(s)
    }
    /// Playback of a resolved stream failed (HTTP 403 / throttled / decoder error): demote the client and
    /// resolve again with the next client in the chain.
    pub async fn stream_retry(&self, id: String, failed_client: String, error: String) -> Result<StreamInfo, CoreError> {
        self.streams.lock().unwrap_or_else(|p| p.into_inner()).remove(&id);
        self.yt.report_client_failure(&failed_client, &error);
        let s = self.yt.stream(&id, self.prefer_mp4, Some(&failed_client)).await?;
        self.streams.lock().unwrap_or_else(|p| p.into_inner()).insert(id, s.clone());
        Ok(s)
    }
    pub fn streaming_health(&self) -> StreamingHealth {
        guard(move || {
            let h = self.yt.health.lock().unwrap_or_else(|p| p.into_inner()).clone();
            StreamingHealth {
                working_client: h.last_client,
                last_error: h.last_error,
                last_ok_at: h.last_ok_at,
                last_error_at: h.last_error_at,
                failures: h.failures,
                clients: yt::client_keys(),
                signed_in: h.signed_in,
                cache_bytes: cache::size(&self.cache_dir()),
                cache_limit_mb: self.cache_limit_mb(),
            }
        })
    }
    /// "Retry" in Settings › Streaming health: forget cached URLs and test a known track.
    pub async fn streaming_selftest(&self) -> Result<StreamingHealth, CoreError> {
        self.streams.lock().unwrap_or_else(|p| p.into_inner()).clear();
        {
            let mut h = self.yt.health.lock().unwrap_or_else(|p| p.into_inner());
            h.preferred = 0;
        }
        let _ = self.yt.stream("J7p4bzqLvCw", self.prefer_mp4, None).await;
        Ok(self.streaming_health())
    }

    // ------------------------------------------------------------------ cache (LRU, survives short outages)
    pub fn set_cache_limit_mb(&self, mb: u32) -> Result<(), CoreError> {
        self.db.kv_set("cache_limit_mb", &mb.to_string())?;
        cache::evict(&self.cache_dir(), mb as u64 * 1024 * 1024);
        Ok(())
    }
    pub fn cache_limit_mb(&self) -> u32 {
        guard(move || {
            self.db.kv_get("cache_limit_mb").and_then(|v| v.parse().ok()).unwrap_or(256)
        })
    }
    /// Cached copy of a streamed track, if present (touches it for LRU).
    pub fn cached_path(&self, id: String) -> Option<String> {
        guard(move || {
            cache::get(&self.cache_dir(), &sanitize(&id))
        })
    }
    /// Pre-cache a track (next 2-3 in the queue). Returns the file path.
    pub async fn prefetch(&self, track: Track) -> Result<String, CoreError> {
        if track.is_local() {
            return Err(CoreError::Invalid { msg: "local".into() });
        }
        if let Some(p) = self.offline_path(track.id.clone()) {
            return Ok(p);
        }
        let dir = self.cache_dir();
        let key = sanitize(&track.id);
        if let Some(p) = cache::get(&dir, &key) {
            return Ok(p);
        }
        let s = self.stream(track.id.clone()).await?;
        if s.is_hls {
            return Err(CoreError::Unavailable { msg: "hls".into() });
        }
        let ext = if s.mime.contains("mp4") { "m4a" } else { "webm" };
        std::fs::create_dir_all(&dir).map_err(io)?;
        let path = format!("{dir}/{key}.{ext}");
        let tmp = format!("{path}.part");
        let pk = format!("cache:{}", track.id);
        let r = fetch_ranged(self.yt.http(), &s, &tmp, &self.progress, &pk).await;
        self.progress.lock().unwrap_or_else(|p| p.into_inner()).remove(&pk);
        if let Err(e) = r {
            let _ = std::fs::remove_file(&tmp);
            return Err(e);
        }
        std::fs::rename(&tmp, &path).map_err(io)?;
        cache::evict(&dir, self.cache_limit_mb() as u64 * 1024 * 1024);
        Ok(path)
    }
    pub fn clear_cache(&self) {
        guard(move || {
            let _ = std::fs::remove_dir_all(self.cache_dir());
        })
    }

    // ------------------------------------------------------------------ optional sign-in (cookie)
    /// Cookie header captured from a music.youtube.com web sign-in. Optional; everything works signed out.
    pub async fn sign_in_cookie(&self, cookie: String) -> Result<(), CoreError> {
        self.yt.rp.user_auth_set_cookie(cookie.clone()).await.map_err(|e| CoreError::Invalid { msg: e.to_string() })?;
        let _ = self.db.kv_set("auth_cookie", &cookie);
        self.yt.health.lock().unwrap_or_else(|p| p.into_inner()).signed_in = true;
        let _ = self.db.kv_set("signed_in", "1");
        Ok(())
    }
    pub async fn sign_out(&self) -> Result<(), CoreError> {
        let _ = self.yt.rp.user_auth_remove_cookie().await;
        let _ = self.db.kv_del("auth_cookie");
        let _ = self.db.kv_del("account_info");
        self.yt.health.lock().unwrap_or_else(|p| p.into_inner()).signed_in = false;
        let _ = self.db.kv_set("signed_in", "0");
        Ok(())
    }
    pub fn is_signed_in(&self) -> bool {
        guard(move || {
            self.db.kv_get("signed_in").as_deref() == Some("1")
        })
    }
    /// Pull liked songs and saved playlists from the signed-in account into the local library.
    pub async fn sync_library(&self) -> Result<SyncResult, CoreError> {
        let q = self.yt.rp.query().authenticated();
        let mut liked = q.music_liked_tracks().await.map_err(|e| CoreError::Network { msg: e.to_string() })?;
        let _ = liked.tracks.extend_limit(&q, 1000).await;
        let mut n = 0;
        for t in liked.tracks.items.iter().rev() {
            if self.db.set_liked(&yt::conv_track(t), true).is_ok() {
                n += 1;
            }
        }
        let mut pl = 0;
        if let Ok(p) = q.music_saved_playlists().await {
            for x in p.items.iter() {
                let _ = self.db.set_saved("playlist", &x.id, serde_json::to_string(&yt::conv_playlist(x)).ok());
                pl += 1;
            }
        }
        Ok(SyncResult { liked: n, playlists: pl })
    }
    /// Feed a stream resolved elsewhere (e.g. the Android NewPipe fallback) into the cache.
    pub fn put_stream(&self, id: String, info: StreamInfo) {
        guard(move || {
            self.streams.lock().unwrap_or_else(|p| p.into_inner()).insert(id, info);
        })
    }
    pub fn invalidate_stream(&self, id: String) {
        guard(move || {
            self.streams.lock().unwrap_or_else(|p| p.into_inner()).remove(&id);
        })
    }
    /// Local file path when the track is downloaded.
    pub fn offline_path(&self, id: String) -> Option<String> {
        guard(move || {
            self.db.download_path(&id).filter(|p| std::path::Path::new(p).exists())
        })
    }

    // ------------------------------------------------------------------ queue
    pub fn queue_state(&self) -> QueueState {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).state()
        })
    }
    pub fn queue_set(&self, tracks: Vec<Track>, start: i32, source: String) -> Option<Track> {
        guard(move || {
            let mut q = self.queue.lock().unwrap_or_else(|p| p.into_inner());
            q.set(tracks, start, source);
            q.current()
        })
    }
    pub fn queue_current(&self) -> Option<Track> {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).current()
        })
    }
    pub fn queue_next(&self, user: bool) -> Option<Track> {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).next(user)
        })
    }
    pub fn queue_prev(&self) -> Option<Track> {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).prev()
        })
    }
    pub fn queue_peek_next(&self) -> Option<Track> {
        guard(move || {
            let q = self.queue.lock().unwrap_or_else(|p| p.into_inner());
            if q.repeat == 2 {
                return q.current();
            }
            q.tracks.get((q.index + 1) as usize).cloned().or_else(|| if q.repeat == 1 { q.tracks.first().cloned() } else { None })
        })
    }
    pub fn queue_jump(&self, index: i32) -> Option<Track> {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).jump(index)
        })
    }
    pub fn queue_play_next(&self, track: Track) {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).play_next(track)
        })
    }
    pub fn queue_add(&self, track: Track) {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).add(track)
        })
    }
    pub fn queue_remove(&self, index: i32) {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).remove(index)
        })
    }
    pub fn queue_move(&self, from: i32, to: i32) {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).move_item(from, to)
        })
    }
    pub fn set_shuffle(&self, on: bool) {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).set_shuffle(on)
        })
    }
    pub fn cycle_repeat(&self) -> u8 {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).cycle_repeat()
        })
    }
    pub fn set_autoplay(&self, on: bool) {
        guard(move || {
            self.queue.lock().unwrap_or_else(|p| p.into_inner()).autoplay = on
        })
    }
    /// Infinite autoplay: when few tracks remain, extend with YT Music radio reranked by the user's stats.
    /// Returns how many tracks were appended.
    pub async fn ensure_autoplay(&self) -> Result<u32, CoreError> {
        let (seed, exclude) = {
            let q = self.queue.lock().unwrap_or_else(|p| p.into_inner());
            if !q.autoplay || q.repeat != 0 || q.remaining() >= 5 {
                return Ok(0);
            }
            // seed from the last streaming track in the queue
            let seed = q.tracks.iter().rev().find(|t| !t.is_local()).cloned();
            (seed, q.tracks.iter().map(|t| t.id.clone()).collect::<HashSet<_>>())
        };
        let Some(seed) = seed else { return Ok(0) };
        let mut r = self.similar(&seed.id, Some(&seed), &exclude).await?;
        r.truncate(25);
        let n = self.queue.lock().unwrap_or_else(|p| p.into_inner()).append_autoplay(r);
        Ok(n as u32)
    }

    // ------------------------------------------------------------------ stats
    pub fn record_play(&self, track: Track, started_at: i64, listened_ms: u64, skipped: bool, tz_offset_min: i32, source: String) -> Result<(), CoreError> {
        if self.db.kv_get("signed_in").as_deref() == Some("1") {
            self.yt.health.lock().unwrap_or_else(|p| p.into_inner()).signed_in = true;
        }
        if listened_ms < 5_000 && !skipped {
            return Ok(());
        }
        self.db.record_play(&track, started_at, listened_ms, skipped, tz_offset_min, &source)?;
        if !skipped && !track.id.starts_with("local:") && self.lastfm_on() && lastfm::qualifies(track.duration_s, listened_ms) {
            let _ = lastfm::enqueue(&self.db, &track, started_at);
        }
        Ok(())
    }

    // ------------------------------------------------------------------ last.fm (optional)
    /// Build-time keys (empty = feature hidden).
    pub fn lastfm_configure(&self, api_key: String, api_secret: String) {
        guard(move || {
            let k = api_key.trim().to_string();
            let s = api_secret.trim().to_string();
            *self.lastfm.lock().unwrap_or_else(|p| p.into_inner()) = if k.is_empty() || s.is_empty() { None } else { Some(lastfm::Keys { key: k, secret: s }) };
        })
    }
    pub fn lastfm_state(&self) -> LastfmState {
        guard(move || {
            // read `available` in its own statement: a guard temporary inside the struct literal would still be
            // held when lastfm_on() locks the same mutex (self-deadlock).
            let available = self.lastfm.lock().unwrap_or_else(|p| p.into_inner()).is_some();
            LastfmState {
                available,
                enabled: self.lastfm_on(),
                username: self.db.kv_get("lastfm_user").unwrap_or_default(),
                pending: lastfm::pending(&self.db),
            }
        })
    }
    /// Step 1: returns the approval URL to open in the browser.
    pub async fn lastfm_begin_auth(&self) -> Result<LastfmAuth, CoreError> {
        let k = self.lastfm_keys()?;
        let (token, url) = lastfm::get_token(self.yt.http(), &k).await?;
        Ok(LastfmAuth { token, url })
    }
    /// Step 2 (after the user approved): stores the session; returns the username.
    pub async fn lastfm_complete_auth(&self, token: String) -> Result<String, CoreError> {
        let k = self.lastfm_keys()?;
        let (sk, name) = lastfm::get_session(self.yt.http(), &k, &token).await?;
        self.db.kv_set("lastfm_sk", &sk)?;
        self.db.kv_set("lastfm_user", &name)?;
        self.db.kv_set("lastfm_on", "1")?;
        Ok(name)
    }
    pub fn lastfm_set_enabled(&self, on: bool) -> Result<(), CoreError> {
        self.db.kv_set("lastfm_on", if on { "1" } else { "0" })
    }
    pub fn lastfm_sign_out(&self) -> Result<(), CoreError> {
        self.db.kv_set("lastfm_sk", "")?;
        self.db.kv_set("lastfm_user", "")?;
        self.db.kv_set("lastfm_on", "0")
    }
    /// Best effort; never fails playback.
    pub async fn lastfm_now_playing(&self, track: Track) {
        if !self.lastfm_on() || track.id.starts_with("local:") {
            return;
        }
        if let (Ok(k), Some(sk)) = (self.lastfm_keys(), self.db.kv_get("lastfm_sk")) {
            let _ = lastfm::now_playing(self.yt.http(), &k, &sk, &track).await;
        }
    }
    /// Sends queued scrobbles (call after record_play and when the network returns).
    pub async fn lastfm_flush(&self) -> Result<u32, CoreError> {
        if !self.lastfm_on() {
            return Ok(0);
        }
        let k = self.lastfm_keys()?;
        let sk = self.db.kv_get("lastfm_sk").unwrap_or_default();
        let mut total = 0;
        loop {
            let n = lastfm::flush(self.yt.http(), &k, &sk, &self.db).await?;
            total += n;
            if n < 50 {
                break;
            }
        }
        Ok(total)
    }
    /// range: "week" | "month" | "year" | "all"
    pub fn stats(&self, range: String, tz_offset_min: i32) -> Result<StatsSummary, CoreError> {
        stats::summary(&self.db, &range, now(), tz_offset_min)
    }
    pub fn recent(&self, limit: u32) -> Result<Vec<Track>, CoreError> {
        self.db.recent_tracks(limit)
    }

    // ------------------------------------------------------------------ library
    pub fn set_liked(&self, track: Track, liked: bool) -> Result<(), CoreError> {
        self.db.set_liked(&track, liked)
    }
    pub fn is_liked(&self, id: String) -> bool {
        guard(move || {
            self.db.is_liked(&id)
        })
    }
    pub fn liked(&self) -> Result<Vec<Track>, CoreError> {
        self.db.liked()
    }
    pub fn playlists(&self) -> Result<Vec<Playlist>, CoreError> {
        self.db.playlists()
    }
    pub fn create_playlist(&self, title: String, tracks: Vec<Track>) -> Result<Playlist, CoreError> {
        let id = format!("local:{}", now() * 1000 + rand::random::<u16>() as i64);
        let thumb = tracks.first().map(|t| t.thumb.clone()).unwrap_or_default();
        self.db.create_playlist(&id, &title, "Playlist", &thumb, "local", &tracks)?;
        Ok(Playlist { id, title, subtitle: "Playlist".into(), thumb, track_count: Some(tracks.len() as u64), source: "local".into() })
    }
    pub fn add_to_playlist(&self, playlist_id: String, track: Track) -> Result<(), CoreError> {
        self.db.add_to_playlist(&playlist_id, &track)
    }
    pub fn delete_playlist(&self, playlist_id: String) -> Result<(), CoreError> {
        self.db.delete_playlist(&playlist_id)
    }
    /// Bulk delete in one SQLite transaction. Returns how many were removed.
    pub fn delete_playlists(&self, ids: Vec<String>) -> Result<u32, CoreError> {
        self.db.delete_playlists(&ids)
    }
    /// Rename / edit details (description, cover url; empty cover keeps the current one).
    pub fn edit_playlist(&self, playlist_id: String, title: String, description: String, cover: String) -> Result<(), CoreError> {
        let t = title.trim();
        if t.is_empty() {
            return Err(CoreError::Invalid { msg: "Name can't be empty".into() });
        }
        self.db.edit_playlist(&playlist_id, t, description.trim(), if cover.is_empty() { None } else { Some(cover.as_str()) })
    }
    pub fn remove_from_playlist(&self, playlist_id: String, position: u32) -> Result<(), CoreError> {
        self.db.remove_from_playlist(&playlist_id, position as i64)
    }
    /// Create with a name the user typed (and optional description / cover). Never creates an untitled list.
    pub fn create_playlist_named(&self, title: String, description: String, cover: String, tracks: Vec<Track>) -> Result<Playlist, CoreError> {
        let t = title.trim().to_string();
        if t.is_empty() {
            return Err(CoreError::Invalid { msg: "Name can't be empty".into() });
        }
        let id = format!("local:{}", now() * 1000 + rand::random::<u16>() as i64);
        let thumb = if !cover.is_empty() { cover } else { tracks.first().map(|t| t.thumb.clone()).unwrap_or_default() };
        let sub = if description.trim().is_empty() { "Playlist".to_string() } else { description.trim().to_string() };
        self.db.create_playlist(&id, &t, &sub, &thumb, "local", &tracks)?;
        Ok(Playlist { id, title: t, subtitle: sub, thumb, track_count: Some(tracks.len() as u64), source: "local".into() })
    }

    // ------------------------------------------------------------------ search history
    pub fn add_recent_search(&self, query: String) -> Result<(), CoreError> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(());
        }
        self.db.add_search(q)
    }
    pub fn recent_searches(&self, limit: u32) -> Vec<String> {
        guard(move || {
            self.db.searches(limit)
        })
    }
    pub fn remove_recent_search(&self, query: String) -> Result<(), CoreError> {
        self.db.remove_search(Some(&query))
    }
    pub fn clear_recent_searches(&self) -> Result<(), CoreError> {
        self.db.remove_search(None)
    }

    // ------------------------------------------------------------------ account
    /// Google account name / handle / avatar (cached; refreshed from InnerTube when `refresh`).
    pub async fn account_info(&self, refresh: bool) -> Result<Option<AccountInfo>, CoreError> {
        let Some(cookie) = self.db.kv_get("auth_cookie") else { return Ok(None) };
        if !refresh {
            if let Some(j) = self.db.kv_get("account_info") {
                let v: serde_json::Value = serde_json::from_str(&j).unwrap_or_default();
                let g = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
                if !g("name").is_empty() {
                    return Ok(Some(AccountInfo { name: g("name"), handle: g("handle"), avatar: g("avatar"), email: g("email") }));
                }
            }
        }
        let a = self.yt.account_info(&cookie).await?;
        let _ = self.db.kv_set("account_info", &serde_json::json!({"name": a.name, "handle": a.handle, "avatar": a.avatar, "email": a.email}).to_string());
        Ok(Some(a))
    }
    /// Search candidates for swapping an import match (songs filter, scored best first).
    pub async fn import_candidates(&self, source: ImportedTrack, query: String) -> Result<Vec<Track>, CoreError> {
        let q = if query.trim().is_empty() { format!("{} {}", source.title, source.artists.split(',').next().unwrap_or("")) } else { query };
        let mut c: Vec<(u32, Track)> = self.yt.search_tracks(&q).await?.into_iter().map(|t| (import::score(&source, &t), t)).collect();
        c.sort_by(|a, b| b.0.cmp(&a.0));
        Ok(c.into_iter().map(|x| x.1).take(10).collect())
    }
    pub fn save_album(&self, album: Album, saved: bool) -> Result<(), CoreError> {
        self.db.set_saved("album", &album.id, if saved { serde_json::to_string(&album).ok() } else { None })
    }
    pub fn save_artist(&self, artist: Artist, saved: bool) -> Result<(), CoreError> {
        self.db.set_saved("artist", &artist.id, if saved { serde_json::to_string(&artist).ok() } else { None })
    }
    pub fn save_playlist(&self, playlist: Playlist, saved: bool) -> Result<(), CoreError> {
        self.db.set_saved("playlist", &playlist.id, if saved { serde_json::to_string(&playlist).ok() } else { None })
    }
    pub fn is_saved(&self, kind: String, id: String) -> bool {
        guard(move || {
            self.db.is_saved(&kind, &id)
        })
    }
    pub fn saved_albums(&self) -> Result<Vec<Album>, CoreError> {
        self.db.saved("album")
    }
    pub fn saved_artists(&self) -> Result<Vec<Artist>, CoreError> {
        self.db.saved("artist")
    }
    pub fn saved_playlists(&self) -> Result<Vec<Playlist>, CoreError> {
        self.db.saved("playlist")
    }
    pub fn library_counts(&self) -> LibraryCounts {
        guard(move || {
            self.db.counts()
        })
    }
    /// Local audio files found by the platform (MediaStore / chosen folder). Ids are `local:<uri>`.
    pub fn set_local_tracks(&self, tracks: Vec<Track>) -> Result<(), CoreError> {
        self.db.set_local_tracks(&tracks)
    }
    pub fn local_tracks(&self) -> Result<Vec<Track>, CoreError> {
        self.db.local_tracks()
    }

    // ------------------------------------------------------------------ downloads
    pub fn downloads(&self) -> Result<Vec<DownloadItem>, CoreError> {
        self.db.downloads()
    }
    pub fn download_progress(&self, id: String) -> Option<f32> {
        guard(move || {
            self.progress.lock().unwrap_or_else(|p| p.into_inner()).get(&id).copied()
        })
    }
    pub async fn download(&self, track: Track) -> Result<DownloadItem, CoreError> {
        if let Some(p) = self.offline_path(track.id.clone()) {
            return Ok(DownloadItem { bytes: std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0), track, path: p, created_at: now() });
        }
        let s = self.stream(track.id.clone()).await?;
        if s.is_hls {
            return Err(CoreError::Unavailable { msg: "Only an HLS stream is available for this track".into() });
        }
        let ext = if s.mime.contains("mp4") { "m4a" } else { "webm" };
        let path = format!("{}/downloads/{}.{}", self.data_dir, sanitize(&track.id), ext);
        let tmp = format!("{path}.part");
        self.progress.lock().unwrap_or_else(|p| p.into_inner()).insert(track.id.clone(), 0.0);
        let res = fetch_ranged(self.yt.http(), &s, &tmp, &self.progress, &track.id).await;
        self.progress.lock().unwrap_or_else(|p| p.into_inner()).remove(&track.id);
        let bytes = res?;
        std::fs::rename(&tmp, &path).map_err(io)?;
        self.db.add_download(&track, &path, bytes)?;
        Ok(DownloadItem { track, path, bytes, created_at: now() })
    }
    pub fn remove_download(&self, id: String) -> Result<(), CoreError> {
        if let Some(p) = self.db.remove_download(&id)? {
            let _ = std::fs::remove_file(p);
        }
        Ok(())
    }
    /// Remove several downloads (multi-select / a whole album or playlist). Returns how many were removed.
    pub fn remove_downloads(&self, ids: Vec<String>) -> Result<u32, CoreError> {
        let mut n = 0;
        for id in ids {
            if let Some(p) = self.db.remove_download(&id)? {
                let _ = std::fs::remove_file(p);
                n += 1;
            }
        }
        Ok(n)
    }
    pub fn remove_all_downloads(&self) -> Result<u32, CoreError> {
        let ids: Vec<String> = self.db.downloads()?.into_iter().map(|d| d.track.id).collect();
        self.remove_downloads(ids)
    }
    pub fn is_downloaded(&self, id: String) -> bool {
        guard(move || {
            self.db.download_path(&id).is_some()
        })
    }

    // ------------------------------------------------------------------ lyrics
    pub async fn lyrics(&self, track: Track) -> Result<Option<Lyrics>, CoreError> {
        let key = format!("lyr:{}", track.id);
        if let Some(j) = self.db.mcache_get(&key) {
            if j == "none" {
                return Ok(None);
            }
            if let Ok(l) = serde_json::from_str::<LyricsJson>(&j) {
                return Ok(Some(l.into()));
            }
        }
        let l = lyrics::fetch(self.yt.http(), &track).await?;
        let _ = self.db.mcache_set(&key, &l.as_ref().map(|l| serde_json::to_string(&LyricsJson::from(l.clone())).unwrap_or_default()).unwrap_or_else(|| "none".into()));
        Ok(l)
    }

    // ------------------------------------------------------------------ import
    pub async fn import_playlist(&self, url: String) -> Result<ImportResult, CoreError> {
        let p = import::fetch_playlist(self.yt.http(), &url).await?;
        if p.tracks.is_empty() {
            return Err(CoreError::Invalid { msg: "No tracks found: is the playlist public?".into() });
        }
        self.import.start(p.tracks.len() as u32);
        let matches = import::match_all(self.yt.clone(), p.tracks, Some(self.import.clone())).await;
        let cancelled = self.import.finish();
        if cancelled {
            return Err(CoreError::Invalid { msg: "Import cancelled".into() });
        }
        Ok(ImportResult { name: p.name, service: p.service, cover: p.cover, matches })
    }
    /// Progress of the running import (done / total / current track).
    pub fn import_progress(&self) -> ImportProgress {
        guard(|| self.import.snapshot())
    }
    /// Stop the running import; `import_playlist` then returns "Import cancelled".
    pub fn import_cancel(&self) {
        guard(|| self.import.cancel())
    }
    /// Save the matched tracks of an import as a library playlist.
    pub fn save_import(&self, name: String, service: String, cover: String, tracks: Vec<Track>) -> Result<Playlist, CoreError> {
        let id = format!("import:{}", now() * 1000 + rand::random::<u16>() as i64);
        let thumb = if cover.is_empty() { tracks.first().map(|t| t.thumb.clone()).unwrap_or_default() } else { cover };
        let sub = format!("Imported from {service}");
        self.db.create_playlist(&id, &name, &sub, &thumb, "import", &tracks)?;
        Ok(Playlist { id, title: name, subtitle: sub, thumb, track_count: Some(tracks.len() as u64), source: "import".into() })
    }

    // ------------------------------------------------------------------ Isa
    pub fn isa_plan(&self, prompt: String) -> IsaPlan {
        guard(move || {
            isa::plan(&prompt)
        })
    }
    pub async fn isa(&self, prompt: String) -> Result<IsaResult, CoreError> {
        let plan = isa::plan(&prompt);
        let mut set = tokio::task::JoinSet::new();
        for (i, s) in plan.seeds.iter().enumerate() {
            let yt = self.yt.clone();
            let s = s.clone();
            set.spawn(async move { (i, yt.search_tracks(&s).await.unwrap_or_default()) });
        }
        let mut per: Vec<(usize, Vec<Track>)> = Vec::new();
        while let Some(Ok(x)) = set.join_next().await {
            per.push(x);
        }
        per.sort_by_key(|x| x.0);
        let aff = self.db.affinity();
        let mut lists: Vec<Vec<Track>> = per
            .into_iter()
            .map(|(_, v)| {
                let v: Vec<Track> = v.into_iter().filter(|t| isa::fits(&plan, &t.title, &t.artists, t.duration_s)).collect();
                queue::rerank(v, &aff, &HashSet::new())
            })
            .collect();
        // interleave seeds so languages / moods mix evenly
        let mut out: Vec<Track> = Vec::new();
        let mut seen = HashSet::new();
        let mut i = 0;
        while out.len() < 30 && lists.iter().any(|l| i < l.len()) {
            for l in lists.iter_mut() {
                if let Some(t) = l.get(i) {
                    if seen.insert(t.id.clone()) && seen.insert(format!("{}|{}", lyrics::clean_title(&t.title).to_lowercase(), t.main_artist().to_lowercase())) {
                        out.push(t.clone());
                    }
                }
            }
            i += 1;
        }
        if out.is_empty() {
            return Err(CoreError::Unavailable { msg: "Isa couldn't find anything for that. Try different words.".into() });
        }
        Ok(IsaResult { plan, tracks: out })
    }
    /// Isa with a length budget (0 = any): trims the queue to fit, shared by both apps.
    pub async fn isa_queue(&self, prompt: String, max_minutes: u32) -> Result<IsaResult, CoreError> {
        let mut r = self.isa(prompt).await?;
        if max_minutes > 0 {
            let budget = max_minutes as u64 * 60;
            let mut total = 0u64;
            let mut keep = Vec::new();
            for t in r.tracks.into_iter() {
                let d = if t.duration_s > 0 { t.duration_s as u64 } else { 210 };
                if total + d > budget + 120 && !keep.is_empty() {
                    break;
                }
                total += d;
                keep.push(t);
            }
            r.tracks = keep;
            r.plan.max_duration_s = Some(budget as u32);
        }
        Ok(r)
    }
    /// Live prompt suggestions for the Isa card while typing (local, instant).
    pub fn isa_suggestions(&self, text: String) -> Vec<String> {
        guard(move || {
            let all = [
                "late night drive, synthwave", "focus, no vocals", "rainy evening, lo-fi", "gym hype, hip hop",
                "Malayalam melodies", "90s Bollywood classics", "chill acoustic morning", "sad songs to cry to",
                "party bangers", "study with piano", "road trip singalongs", "indie for a sunny day",
                "deep house for work", "Tamil hits", "throwback 2000s pop", "calm sleep music",
            ];
            let q = text.trim().to_lowercase();
            let mut v: Vec<String> = Vec::new();
            if !q.is_empty() {
                for a in all.iter() {
                    if a.contains(&q) || q.split_whitespace().any(|w| w.len() > 2 && a.contains(w)) {
                        v.push(a.to_string());
                    }
                }
                let top = self.db.affinity();
                let mut artists: Vec<(&String, &u32)> = top.plays.iter().collect();
                artists.sort_by(|a, b| b.1.cmp(a.1));
                for (a, _) in artists.into_iter().take(30) {
                    if a.contains(&q) {
                        v.insert(0, format!("songs like {a}"));
                    }
                }
            }
            v.truncate(5);
            v
        })
    }
    /// One line on why a track is playing (for "Ask Isa · Why this song?").
    pub fn isa_why(&self, track: Track) -> String {
        guard(move || {
            let aff = self.db.affinity();
            let artist = track.main_artist();
            let plays = aff.plays.get(&artist.to_lowercase()).copied().unwrap_or(0);
            let tplays = aff.track_plays.get(&track.id).copied().unwrap_or(0);
            let g = isa::genre_of(&track.title, &track.artists, track.album.as_deref().unwrap_or(""));
            if tplays >= 3 {
                format!("You've played this {tplays} times. It's one of your regulars.")
            } else if plays >= 5 {
                format!("You keep coming back to {artist} ({plays} plays), so it's ranked higher in your radio.")
            } else if g != "Other" {
                format!("It fits the {g} you've been listening to, and it's popular with people who play similar songs.")
            } else {
                "It's on the radio for the song you started, and you haven't skipped this artist.".into()
            }
        })
    }

    // ------------------------------------------------------------------ session (restore after relaunch)
    /// Saves the current queue (tracks, index, shuffle/repeat, autoplay tail, source) and the position.
    /// Cheap to call often: the queue is only rewritten when it changed, otherwise just the position.
    /// Clients call it every ~5 s while playing, and on pause, track change and app quit.
    pub fn save_session(&self, position_ms: u64) -> Result<(), CoreError> {
        let st = self.queue.lock().unwrap_or_else(|p| p.into_inner()).state();
        if st.index < 0 || st.tracks.is_empty() {
            return Ok(());
        }
        let q = serde_json::json!({
            "tracks": st.tracks, "index": st.index, "shuffle": st.shuffle, "repeat": st.repeat,
            "autoplay_from": st.autoplay_from, "source_title": st.source_title,
        })
        .to_string();
        let mut sig = self.session_sig.lock().unwrap_or_else(|p| p.into_inner());
        if *sig != q {
            self.db.kv_set("session_queue", &q)?;
            *sig = q;
        }
        drop(sig);
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        self.db.kv_set("session_pos", &format!("{position_ms} {now}"))
    }

    /// The saved session, loaded back into the core queue (nothing plays; no network). None when there is none.
    pub fn restore_session(&self) -> Option<Session> {
        guard(move || {
            let q: serde_json::Value = serde_json::from_str(&self.db.kv_get("session_queue")?).ok()?;
            let tracks: Vec<Track> = serde_json::from_value(q.get("tracks")?.clone()).ok()?;
            if tracks.is_empty() {
                return None;
            }
            let index = (q.get("index")?.as_i64()? as i32).clamp(0, tracks.len() as i32 - 1);
            let (pos, at) = self
                .db
                .kv_get("session_pos")
                .map(|v| {
                    let mut it = v.split_whitespace();
                    (it.next().and_then(|x| x.parse().ok()).unwrap_or(0u64), it.next().and_then(|x| x.parse().ok()).unwrap_or(0i64))
                })
                .unwrap_or((0, 0));
            let s = Session {
                index,
                position_ms: pos,
                shuffle: q.get("shuffle").and_then(|v| v.as_bool()).unwrap_or(false),
                repeat: q.get("repeat").and_then(|v| v.as_u64()).unwrap_or(0).min(2) as u8,
                autoplay_from: q.get("autoplay_from").and_then(|v| v.as_i64()).unwrap_or(-1) as i32,
                source_title: q.get("source_title").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                saved_at: at,
                tracks,
            };
            let mut qu = self.queue.lock().unwrap_or_else(|p| p.into_inner());
            qu.tracks = s.tracks.clone();
            qu.original = s.tracks.clone();
            qu.index = s.index;
            qu.shuffle = s.shuffle;
            qu.repeat = s.repeat;
            qu.autoplay_from = if s.autoplay_from >= 0 && (s.autoplay_from as usize) < s.tracks.len() { s.autoplay_from } else { -1 };
            qu.source_title = s.source_title.clone();
            drop(qu);
            *self.session_sig.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
            Some(s)
        })
    }

    /// Forgets the saved session.
    pub fn clear_session(&self) {
        guard(move || {
            let _ = self.db.kv_del("session_queue");
            let _ = self.db.kv_del("session_pos");
            *self.session_sig.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
        })
    }

    // ------------------------------------------------------------------ settings kv
    pub fn get_setting(&self, key: String) -> Option<String> {
        guard(move || {
            self.db.kv_get(&format!("set:{key}"))
        })
    }
    pub fn set_setting(&self, key: String, value: String) -> Result<(), CoreError> {
        self.db.kv_set(&format!("set:{key}"), &value)
    }

    // ------------------------------------------------------------------ updates
    pub async fn check_update(&self, repo: String, current_version: String, token: Option<String>) -> Result<UpdateInfo, CoreError> {
        update::check(&repo, &current_version, token.as_deref()).await
    }
    /// Downloads a release asset (API url) to `dest`. Progress via `download_progress("update")`.
    pub async fn download_update(&self, asset_api_url: String, token: Option<String>, dest: String) -> Result<u64, CoreError> {
        update::download(&asset_api_url, token.as_deref(), &dest, &self.progress).await
    }
}

impl Core {
    fn lastfm_on(&self) -> bool {
        let configured = self.lastfm.lock().unwrap_or_else(|p| p.into_inner()).is_some();
        configured
            && self.db.kv_get("lastfm_on").as_deref() == Some("1")
            && !self.db.kv_get("lastfm_sk").unwrap_or_default().is_empty()
    }
    fn lastfm_keys(&self) -> Result<lastfm::Keys, CoreError> {
        let g = self.lastfm.lock().unwrap_or_else(|p| p.into_inner());
        let k = g.as_ref().ok_or(CoreError::Invalid { msg: "Last.fm is not configured in this build".into() })?;
        Ok(lastfm::Keys { key: k.key.clone(), secret: k.secret.clone() })
    }
    fn cache_dir(&self) -> String {
        format!("{}/cache", self.data_dir)
    }
}

fn sanitize(s: &str) -> String {
    s.chars().map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '_' }).collect()
}

async fn fetch_ranged(
    http: &reqwest::Client,
    s: &StreamInfo,
    dest: &str,
    progress: &Arc<Mutex<HashMap<String, f32>>>,
    key: &str,
) -> Result<u64, CoreError> {
    use tokio::io::AsyncWriteExt;
    let mut f = tokio::fs::File::create(dest).await.map_err(io)?;
    let total = s.content_length.unwrap_or(0);
    let chunk: u64 = 1 << 20; // googlevideo throttles big single requests; 1 MiB ranges stay fast
    let mut off: u64 = 0;
    loop {
        let end = off + chunk - 1;
        let r = http
            .get(&s.url)
            .header("User-Agent", &s.user_agent)
            .header("Range", format!("bytes={off}-{end}"))
            .send()
            .await
            .map_err(|e| CoreError::Network { msg: e.to_string() })?;
        if !r.status().is_success() {
            return Err(CoreError::Network { msg: format!("HTTP {}", r.status().as_u16()) });
        }
        let b = r.bytes().await.map_err(|e| CoreError::Network { msg: e.to_string() })?;
        if b.is_empty() {
            break;
        }
        f.write_all(&b).await.map_err(io)?;
        off += b.len() as u64;
        if total > 0 {
            progress.lock().unwrap_or_else(|p| p.into_inner()).insert(key.to_string(), (off as f32 / total as f32).min(1.0));
        }
        if (b.len() as u64) < chunk || (total > 0 && off >= total) {
            break;
        }
    }
    f.flush().await.map_err(io)?;
    Ok(off)
}

fn suggestions_for(hour: i64) -> Vec<String> {
    match hour {
        5..=11 => vec!["morning coffee, acoustic".into(), "focus, no vocals".into(), "upbeat Malayalam".into()],
        12..=16 => vec!["focus, no vocals".into(), "feel good pop".into(), "Tamil hits".into()],
        17..=21 => vec!["rainy night drive, Malayalam + lo-fi".into(), "gym, hip-hop".into(), "chill evening".into()],
        _ => vec!["late night lo-fi".into(), "sad songs, Hindi".into(), "sleep ambient".into()],
    }
}

// Serde mirrors for caching (uniffi records stay plain).
#[derive(serde::Serialize, serde::Deserialize)]
struct MixJson {
    id: String,
    title: String,
    subtitle: String,
    thumb: String,
    seed: Option<String>,
    tracks: Vec<Track>,
}
impl From<Mix> for MixJson {
    fn from(m: Mix) -> Self {
        Self { id: m.id, title: m.title, subtitle: m.subtitle, thumb: m.thumb, seed: m.seed_track_id, tracks: m.tracks }
    }
}
impl From<MixJson> for Mix {
    fn from(m: MixJson) -> Self {
        Self { id: m.id, title: m.title, subtitle: m.subtitle, thumb: m.thumb, seed_track_id: m.seed, tracks: m.tracks }
    }
}
#[derive(serde::Serialize, serde::Deserialize)]
struct LyricsJson {
    synced: bool,
    lines: Vec<(u32, u32, String)>,
    source: String,
}
impl From<Lyrics> for LyricsJson {
    fn from(l: Lyrics) -> Self {
        Self { synced: l.synced, lines: l.lines.into_iter().map(|x| (x.start_ms, x.end_ms, x.text)).collect(), source: l.source }
    }
}
impl From<LyricsJson> for Lyrics {
    fn from(l: LyricsJson) -> Self {
        Lyrics {
            synced: l.synced,
            lines: l
                .lines
                .into_iter()
                .map(|(s, e, t)| LyricLine { words: if l.synced { lyrics::words(&t, s, e) } else { vec![] }, start_ms: s, end_ms: e, text: t })
                .collect(),
            source: l.source,
        }
    }
}

#[cfg(test)]
mod live_tests;
