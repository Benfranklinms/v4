//! itune-core: the shared engine behind the Android and macOS apps (exposed with UniFFI).

pub mod cache;
pub mod db;
pub mod import;
pub mod isa;
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
    pub async fn album(&self, id: String) -> Result<AlbumPage, CoreError> {
        self.yt.album(&id).await
    }
    pub async fn artist(&self, id: String) -> Result<ArtistPage, CoreError> {
        self.yt.artist(&id).await
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
        self.yt.playlist(&id).await
    }
    pub async fn radio(&self, video_id: String) -> Result<Vec<Track>, CoreError> {
        let c = self.yt.radio(&video_id).await?;
        let aff = self.db.affinity();
        let mut ex = HashSet::new();
        ex.insert(video_id.clone());
        let mut r = queue::rerank(c, &aff, &ex);
        r.truncate(40);
        Ok(r)
    }

    pub async fn home(&self, tz_offset_min: i32) -> Result<HomeFeed, CoreError> {
        let local = now() + tz_offset_min as i64 * 60;
        let hour = (local.rem_euclid(86_400)) / 3600;
        let greeting = match hour {
            5..=11 => "Good morning",
            12..=16 => "Good afternoon",
            17..=21 => "Good evening",
            _ => "Late night",
        }
        .to_string();
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
        let trending = self.yt.charts().await.unwrap_or_default();
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
        Ok(HomeFeed { greeting, mixes, recent, trending: trending.into_iter().take(20).collect(), suggestions })
    }

    // ------------------------------------------------------------------ streams
    /// Resolve a stream URL (cached until shortly before it expires).
    pub async fn stream(&self, id: String) -> Result<StreamInfo, CoreError> {
        if let Some(s) = self.streams.lock().unwrap().get(&id).cloned() {
            if s.expires_at - now() > 600 {
                return Ok(s);
            }
        }
        let s = self.yt.stream(&id, self.prefer_mp4, None).await?;
        self.streams.lock().unwrap().insert(id, s.clone());
        Ok(s)
    }
    /// Playback of a resolved stream failed (HTTP 403 / throttled / decoder error): demote the client and
    /// resolve again with the next client in the chain.
    pub async fn stream_retry(&self, id: String, failed_client: String, error: String) -> Result<StreamInfo, CoreError> {
        self.streams.lock().unwrap().remove(&id);
        self.yt.report_client_failure(&failed_client, &error);
        let s = self.yt.stream(&id, self.prefer_mp4, Some(&failed_client)).await?;
        self.streams.lock().unwrap().insert(id, s.clone());
        Ok(s)
    }
    pub fn streaming_health(&self) -> StreamingHealth {
        let h = self.yt.health.lock().unwrap().clone();
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
    }
    /// "Retry" in Settings › Streaming health: forget cached URLs and test a known track.
    pub async fn streaming_selftest(&self) -> Result<StreamingHealth, CoreError> {
        self.streams.lock().unwrap().clear();
        {
            let mut h = self.yt.health.lock().unwrap();
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
        self.db.kv_get("cache_limit_mb").and_then(|v| v.parse().ok()).unwrap_or(1024)
    }
    /// Cached copy of a streamed track, if present (touches it for LRU).
    pub fn cached_path(&self, id: String) -> Option<String> {
        cache::get(&self.cache_dir(), &sanitize(&id))
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
        self.progress.lock().unwrap().remove(&pk);
        if let Err(e) = r {
            let _ = std::fs::remove_file(&tmp);
            return Err(e);
        }
        std::fs::rename(&tmp, &path).map_err(io)?;
        cache::evict(&dir, self.cache_limit_mb() as u64 * 1024 * 1024);
        Ok(path)
    }
    pub fn clear_cache(&self) {
        let _ = std::fs::remove_dir_all(self.cache_dir());
    }

    // ------------------------------------------------------------------ optional sign-in (cookie)
    /// Cookie header captured from a music.youtube.com web sign-in. Optional; everything works signed out.
    pub async fn sign_in_cookie(&self, cookie: String) -> Result<(), CoreError> {
        self.yt.rp.user_auth_set_cookie(cookie).await.map_err(|e| CoreError::Invalid { msg: e.to_string() })?;
        self.yt.health.lock().unwrap().signed_in = true;
        let _ = self.db.kv_set("signed_in", "1");
        Ok(())
    }
    pub async fn sign_out(&self) -> Result<(), CoreError> {
        let _ = self.yt.rp.user_auth_remove_cookie().await;
        self.yt.health.lock().unwrap().signed_in = false;
        let _ = self.db.kv_set("signed_in", "0");
        Ok(())
    }
    pub fn is_signed_in(&self) -> bool {
        self.db.kv_get("signed_in").as_deref() == Some("1")
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
        self.streams.lock().unwrap().insert(id, info);
    }
    pub fn invalidate_stream(&self, id: String) {
        self.streams.lock().unwrap().remove(&id);
    }
    /// Local file path when the track is downloaded.
    pub fn offline_path(&self, id: String) -> Option<String> {
        self.db.download_path(&id).filter(|p| std::path::Path::new(p).exists())
    }

    // ------------------------------------------------------------------ queue
    pub fn queue_state(&self) -> QueueState {
        self.queue.lock().unwrap().state()
    }
    pub fn queue_set(&self, tracks: Vec<Track>, start: i32, source: String) -> Option<Track> {
        let mut q = self.queue.lock().unwrap();
        q.set(tracks, start, source);
        q.current()
    }
    pub fn queue_current(&self) -> Option<Track> {
        self.queue.lock().unwrap().current()
    }
    pub fn queue_next(&self, user: bool) -> Option<Track> {
        self.queue.lock().unwrap().next(user)
    }
    pub fn queue_prev(&self) -> Option<Track> {
        self.queue.lock().unwrap().prev()
    }
    pub fn queue_peek_next(&self) -> Option<Track> {
        let q = self.queue.lock().unwrap();
        if q.repeat == 2 {
            return q.current();
        }
        q.tracks.get((q.index + 1) as usize).cloned().or_else(|| if q.repeat == 1 { q.tracks.first().cloned() } else { None })
    }
    pub fn queue_jump(&self, index: i32) -> Option<Track> {
        self.queue.lock().unwrap().jump(index)
    }
    pub fn queue_play_next(&self, track: Track) {
        self.queue.lock().unwrap().play_next(track)
    }
    pub fn queue_add(&self, track: Track) {
        self.queue.lock().unwrap().add(track)
    }
    pub fn queue_remove(&self, index: i32) {
        self.queue.lock().unwrap().remove(index)
    }
    pub fn queue_move(&self, from: i32, to: i32) {
        self.queue.lock().unwrap().move_item(from, to)
    }
    pub fn set_shuffle(&self, on: bool) {
        self.queue.lock().unwrap().set_shuffle(on)
    }
    pub fn cycle_repeat(&self) -> u8 {
        self.queue.lock().unwrap().cycle_repeat()
    }
    pub fn set_autoplay(&self, on: bool) {
        self.queue.lock().unwrap().autoplay = on
    }
    /// Infinite autoplay: when few tracks remain, extend with YT Music radio reranked by the user's stats.
    /// Returns how many tracks were appended.
    pub async fn ensure_autoplay(&self) -> Result<u32, CoreError> {
        let (seed, exclude) = {
            let q = self.queue.lock().unwrap();
            if !q.autoplay || q.repeat != 0 || q.remaining() >= 3 {
                return Ok(0);
            }
            // seed from the last streaming track in the queue
            let seed = q.tracks.iter().rev().find(|t| !t.is_local()).cloned();
            (seed, q.tracks.iter().map(|t| t.id.clone()).collect::<HashSet<_>>())
        };
        let Some(seed) = seed else { return Ok(0) };
        let cands = self.yt.radio(&seed.id).await?;
        let aff = self.db.affinity();
        let mut r = queue::rerank(cands, &aff, &exclude);
        r.truncate(25);
        let n = self.queue.lock().unwrap().append_autoplay(r);
        Ok(n as u32)
    }

    // ------------------------------------------------------------------ stats
    pub fn record_play(&self, track: Track, started_at: i64, listened_ms: u64, skipped: bool, tz_offset_min: i32, source: String) -> Result<(), CoreError> {
        if self.db.kv_get("signed_in").as_deref() == Some("1") {
            self.yt.health.lock().unwrap().signed_in = true;
        }
        if listened_ms < 5_000 && !skipped {
            return Ok(());
        }
        self.db.record_play(&track, started_at, listened_ms, skipped, tz_offset_min, &source)
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
        self.db.is_liked(&id)
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
        self.db.is_saved(&kind, &id)
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
        self.db.counts()
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
        self.progress.lock().unwrap().get(&id).copied()
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
        self.progress.lock().unwrap().insert(track.id.clone(), 0.0);
        let res = fetch_ranged(self.yt.http(), &s, &tmp, &self.progress, &track.id).await;
        self.progress.lock().unwrap().remove(&track.id);
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

    // ------------------------------------------------------------------ lyrics
    pub async fn lyrics(&self, track: Track) -> Result<Option<Lyrics>, CoreError> {
        let key = format!("lyr:{}", track.id);
        if let Some(j) = self.db.kv_get(&key) {
            if j == "none" {
                return Ok(None);
            }
            if let Ok(l) = serde_json::from_str::<LyricsJson>(&j) {
                return Ok(Some(l.into()));
            }
        }
        let l = lyrics::fetch(self.yt.http(), &track).await?;
        let _ = self.db.kv_set(&key, &l.as_ref().map(|l| serde_json::to_string(&LyricsJson::from(l.clone())).unwrap_or_default()).unwrap_or_else(|| "none".into()));
        Ok(l)
    }

    // ------------------------------------------------------------------ import
    pub async fn import_playlist(&self, url: String) -> Result<ImportResult, CoreError> {
        let p = import::fetch_playlist(self.yt.http(), &url).await?;
        if p.tracks.is_empty() {
            return Err(CoreError::Invalid { msg: "No tracks found: is the playlist public?".into() });
        }
        let matches = import::match_all(self.yt.clone(), p.tracks).await;
        Ok(ImportResult { name: p.name, service: p.service, cover: p.cover, matches })
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
        isa::plan(&prompt)
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
    /// One line on why a track is playing (for "Ask Isa · Why this song?").
    pub fn isa_why(&self, track: Track) -> String {
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
    }

    // ------------------------------------------------------------------ settings kv
    pub fn get_setting(&self, key: String) -> Option<String> {
        self.db.kv_get(&format!("set:{key}"))
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
            progress.lock().unwrap().insert(key.to_string(), (off as f32 / total as f32).min(1.0));
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
