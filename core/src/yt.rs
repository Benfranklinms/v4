//! YouTube Music access: catalogue via rustypipe, streams via our own JS-less InnerTube player.

use crate::model::*;
use crate::CoreError;
use rustypipe::client::RustyPipe;
use rustypipe::model as rp;
use rustypipe::model::richtext::ToPlaintext;
use serde_json::{json, Value};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn best_thumb(t: &[rp::Thumbnail]) -> String {
    let mut best = t.iter().max_by_key(|x| x.width).map(|x| x.url.clone()).unwrap_or_default();
    // YT Music art urls (lh3.googleusercontent.com/...=w120-h120-...) can be upscaled.
    if let Some(i) = best.find("=w") {
        if best.contains("googleusercontent") {
            best.truncate(i);
            best.push_str("=w544-h544-l90-rj");
        }
    }
    best
}

fn join_artists(a: &[rp::ArtistId]) -> String {
    a.iter().map(|x| x.name.as_str()).collect::<Vec<_>>().join(", ")
}

pub fn conv_track(t: &rp::TrackItem) -> Track {
    Track {
        id: t.id.clone(),
        title: t.name.clone(),
        artists: join_artists(&t.artists),
        artist_id: t.artist_id.clone().or_else(|| t.artists.iter().find_map(|a| a.id.clone())),
        album: t.album.as_ref().map(|a| a.name.clone()),
        album_id: t.album.as_ref().map(|a| a.id.clone()),
        duration_s: t.duration.unwrap_or(0),
        thumb: best_thumb(&t.cover),
    }
}

pub fn conv_artist(a: &rp::ArtistItem) -> Artist {
    Artist {
        id: a.id.clone(),
        name: a.name.clone(),
        thumb: best_thumb(&a.avatar),
        subtitle: a.subscriber_count.map(|c| format!("{} subscribers", compact(c))),
    }
}

pub fn conv_album(a: &rp::AlbumItem) -> Album {
    Album {
        id: a.id.clone(),
        title: a.name.clone(),
        artists: join_artists(&a.artists),
        year: a.year,
        thumb: best_thumb(&a.cover),
        kind: format!("{:?}", a.album_type),
    }
}

pub fn conv_playlist(p: &rp::MusicPlaylistItem) -> Playlist {
    Playlist {
        id: p.id.clone(),
        title: p.name.clone(),
        subtitle: p.channel.as_ref().map(|c| c.name.clone()).unwrap_or_else(|| "YouTube Music".into()),
        thumb: best_thumb(&p.thumbnail),
        track_count: p.track_count,
        source: "yt".into(),
    }
}

fn compact(n: u64) -> String {
    if n >= 1_000_000 {
        format!("{:.1}M", n as f64 / 1e6)
    } else if n >= 1000 {
        format!("{:.0}K", n as f64 / 1e3)
    } else {
        n.to_string()
    }
}

struct Client {
    key: &'static str,
    name: &'static str,
    id: u32,
    version: &'static str,
    ua: &'static str,
    extra: fn() -> Value,
}

// Client definitions follow yt-dlp master (2026). JS-less clients only: their stream URLs are
// not signature-ciphered, so no player-JS deobfuscation is needed.
const CLIENTS: &[Client] = &[
    Client {
        key: "visionos",
        name: "VISIONOS",
        id: 101,
        version: "1.02",
        ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        extra: || json!({"deviceMake":"Apple","deviceModel":"RealityDevice17,1","osName":"visionOS","osVersion":"26.5.23O471"}),
    },
    Client {
        key: "android_vr",
        name: "ANDROID_VR",
        id: 28,
        version: "1.65.10",
        ua: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        extra: || json!({"deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L"}),
    },
    Client {
        key: "ios",
        name: "IOS",
        id: 5,
        version: "21.26.4",
        ua: "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extra: || json!({"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82"}),
    },
];

pub struct Yt {
    pub rp: RustyPipe,
    http: reqwest::Client,
    visitor: Mutex<Option<(String, i64)>>,
    pub health: Mutex<Health>,
}

#[derive(Default, Clone)]
pub struct Health {
    /// index into CLIENTS that last worked (tried first)
    pub preferred: usize,
    pub last_client: String,
    pub last_error: String,
    pub last_ok_at: i64,
    pub last_error_at: i64,
    pub failures: u32,
    pub signed_in: bool,
}

pub fn client_keys() -> Vec<String> {
    let mut v: Vec<String> = CLIENTS.iter().map(|c| c.key.to_string()).collect();
    v.push("rustypipe".into());
    v
}

impl Yt {
    pub fn new(storage_dir: &str) -> Result<Self, CoreError> {
        let rp = RustyPipe::builder()
            .storage_dir(storage_dir)
            .build()
            .map_err(|e| CoreError::Network { msg: e.to_string() })?;
        let http = reqwest::Client::builder()
            .gzip(true)
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .map_err(|e| CoreError::Network { msg: e.to_string() })?;
        Ok(Self { rp, http, visitor: Mutex::new(None), health: Mutex::new(Health::default()) })
    }

    pub fn http(&self) -> &reqwest::Client {
        &self.http
    }

    pub async fn search(&self, q: &str) -> Result<SearchResults, CoreError> {
        let main = self.rp.query().music_search_main(q).await.map_err(net)?;
        let mut out = SearchResults {
            top: None,
            tracks: vec![],
            artists: vec![],
            albums: vec![],
            playlists: vec![],
            corrected_query: main.corrected_query.clone(),
        };
        for it in &main.items.items {
            match it {
                rp::MusicItem::Track(t) => out.tracks.push(conv_track(t)),
                rp::MusicItem::Artist(a) => out.artists.push(conv_artist(a)),
                rp::MusicItem::Album(a) => out.albums.push(conv_album(a)),
                rp::MusicItem::Playlist(p) => out.playlists.push(conv_playlist(p)),
                _ => {}
            }
        }
        // Main search mixes videos; make sure we have a decent list of songs.
        if out.tracks.len() < 8 {
            if let Ok(t) = self.rp.query().music_search_tracks(q).await {
                for x in t.items.items.iter() {
                    let tr = conv_track(x);
                    if !out.tracks.iter().any(|y| y.id == tr.id) {
                        out.tracks.push(tr);
                    }
                }
            }
        }
        out.top = out.tracks.first().cloned();
        Ok(out)
    }

    pub async fn search_tracks(&self, q: &str) -> Result<Vec<Track>, CoreError> {
        let r = self.rp.query().music_search_tracks(q).await.map_err(net)?;
        Ok(r.items.items.iter().map(conv_track).collect())
    }

    pub async fn suggestions(&self, q: &str) -> Result<Vec<String>, CoreError> {
        let r = self.rp.query().music_search_suggestion(q).await.map_err(net)?;
        Ok(r.terms.into_iter().collect())
    }

    pub async fn album(&self, id: &str) -> Result<AlbumPage, CoreError> {
        let a = self.rp.query().music_album(id).await.map_err(net)?;
        let thumb = best_thumb(&a.cover);
        let mut tracks: Vec<Track> = a.tracks.iter().map(conv_track).collect();
        for t in tracks.iter_mut() {
            if t.thumb.is_empty() {
                t.thumb = thumb.clone();
            }
            if t.album.is_none() {
                t.album = Some(a.name.clone());
                t.album_id = Some(a.id.clone());
            }
        }
        Ok(AlbumPage {
            album: Album {
                id: a.id.clone(),
                title: a.name.clone(),
                artists: join_artists(&a.artists),
                year: a.year,
                thumb,
                kind: format!("{:?}", a.album_type),
            },
            tracks,
            description: a.description.map(|d| d.to_plaintext()),
        })
    }

    pub async fn artist(&self, id: &str) -> Result<ArtistPage, CoreError> {
        let a = self.rp.query().music_artist(id, false).await.map_err(net)?;
        Ok(ArtistPage {
            artist: Artist {
                id: a.id.clone(),
                name: a.name.clone(),
                thumb: best_thumb(&a.header_image),
                subtitle: a.subscriber_count.map(|c| format!("{} subscribers", compact(c))),
            },
            description: a.description.clone(),
            top_tracks: a.tracks.iter().map(conv_track).collect(),
            albums: a.albums.iter().map(conv_album).collect(),
            similar: a.similar_artists.iter().map(conv_artist).collect(),
        })
    }

    pub async fn playlist(&self, id: &str) -> Result<PlaylistPage, CoreError> {
        let mut p = self.rp.query().music_playlist(id).await.map_err(net)?;
        // pull up to ~300 tracks
        let _ = p.tracks.extend_limit(&self.rp.query(), 300).await;
        Ok(PlaylistPage {
            playlist: Playlist {
                id: p.id.clone(),
                title: p.name.clone(),
                subtitle: p.channel.as_ref().map(|c| c.name.clone()).unwrap_or_else(|| "YouTube Music".into()),
                thumb: best_thumb(&p.thumbnail),
                track_count: p.track_count,
                source: "yt".into(),
            },
            tracks: p.tracks.items.iter().map(conv_track).collect(),
        })
    }

    pub async fn radio(&self, video_id: &str) -> Result<Vec<Track>, CoreError> {
        let r = self.rp.query().music_radio_track(video_id).await.map_err(net)?;
        Ok(r.items.iter().map(conv_track).collect())
    }

    pub async fn charts(&self) -> Result<Vec<Track>, CoreError> {
        let c = self.rp.query().music_charts(None).await.map_err(net)?;
        let mut v: Vec<Track> = c.trending_tracks.iter().map(conv_track).collect();
        v.extend(c.top_tracks.iter().map(conv_track));
        Ok(v)
    }

    async fn visitor_data(&self) -> Option<String> {
        if let Some((v, t)) = self.visitor.lock().unwrap_or_else(|p| p.into_inner()).clone() {
            if now() - t < 6 * 3600 {
                return Some(v);
            }
        }
        let txt = self
            .http
            .get("https://www.youtube.com/sw.js_data")
            .header("User-Agent", CLIENTS[0].ua)
            .send()
            .await
            .ok()?
            .text()
            .await
            .ok()?;
        let txt = txt.trim_start_matches(")]}'").trim();
        let v: Value = serde_json::from_str(txt).ok()?;
        let vd = v.pointer("/0/2/0/0/13")?.as_str()?.to_string();
        *self.visitor.lock().unwrap_or_else(|p| p.into_inner()) = Some((vd.clone(), now()));
        Some(vd)
    }

    /// Resolve a playable audio stream. `prefer_mp4` picks AAC/m4a (AVPlayer), otherwise the best of Opus/AAC.
    /// Clients are tried starting with the one that last worked; `skip_client` skips a client that just failed
    /// at playback (403 / throttled) so the retry goes to the next one.
    pub async fn stream(&self, video_id: &str, prefer_mp4: bool, skip_client: Option<&str>) -> Result<StreamInfo, CoreError> {
        let visitor = self.visitor_data().await;
        let pref = self.health.lock().unwrap_or_else(|p| p.into_inner()).preferred;
        let n = CLIENTS.len();
        let mut errs: Vec<String> = Vec::new();
        for k in 0..n {
            let i = (pref + k) % n;
            let c = &CLIENTS[i];
            if Some(c.key) == skip_client {
                continue;
            }
            match self.player_with(c, video_id, visitor.as_deref(), prefer_mp4).await {
                Ok(s) => {
                    let mut h = self.health.lock().unwrap_or_else(|p| p.into_inner());
                    h.preferred = i;
                    h.last_client = c.key.into();
                    h.last_ok_at = now();
                    h.failures = 0;
                    return Ok(s);
                }
                Err(e) => errs.push(format!("{}: {}", c.key, e)),
            }
        }
        // Last resort: rustypipe's own player (deobfuscating clients; uses the sign-in cookie when present).
        if skip_client != Some("rustypipe") {
            match self.rp.query().player(video_id).await {
                Ok(p) => {
                    let filter = rustypipe::param::StreamFilter::new().no_video();
                    let (_v, a) = p.select_video_audio_stream(&filter);
                    if let Some(a) = a {
                        let mut h = self.health.lock().unwrap_or_else(|p| p.into_inner());
                        h.last_client = "rustypipe".into();
                        h.last_ok_at = now();
                        h.failures = 0;
                        return Ok(StreamInfo {
                            url: a.url.clone(),
                            mime: a.mime.clone(),
                            bitrate: a.bitrate,
                            content_length: Some(a.size),
                            user_agent: CLIENTS[0].ua.into(),
                            expires_at: now() + 5 * 3600,
                            is_hls: false,
                            loudness_db: None,
                            client: "rustypipe".into(),
                            duration_ms: p.details.duration as u64 * 1000,
                        });
                    }
                    errs.push("rustypipe: no audio".into());
                }
                Err(e) => errs.push(format!("rustypipe: {e}")),
            }
        }
        let msg = errs.join("; ");
        let mut h = self.health.lock().unwrap_or_else(|p| p.into_inner());
        h.last_error = msg.clone();
        h.last_error_at = now();
        h.failures += 1;
        Err(CoreError::Unavailable { msg })
    }

    /// Playback of a resolved URL failed (403, throttling): demote that client.
    pub fn report_client_failure(&self, client: &str, error: &str) {
        let mut h = self.health.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(i) = CLIENTS.iter().position(|c| c.key == client) {
            if h.preferred == i {
                h.preferred = (i + 1) % CLIENTS.len();
            }
        }
        h.last_error = format!("{client}: {error}");
        h.last_error_at = now();
        h.failures += 1;
    }

    async fn player_with(
        &self,
        c: &Client,
        video_id: &str,
        visitor: Option<&str>,
        prefer_mp4: bool,
    ) -> Result<StreamInfo, String> {
        let mut client = json!({
            "clientName": c.name, "clientVersion": c.version, "userAgent": c.ua,
            "hl": "en", "gl": "US", "timeZone": "UTC", "utcOffsetMinutes": 0
        });
        if let (Some(o), Value::Object(extra)) = (client.as_object_mut(), (c.extra)()) {
            o.extend(extra);
            if let Some(v) = visitor {
                o.insert("visitorData".into(), Value::String(v.into()));
            }
        }
        let body = json!({
            "context": {"client": client, "user": {"lockedSafetyMode": false}},
            "videoId": video_id,
            "contentCheckOk": true,
            "racyCheckOk": true,
            "playbackContext": {"contentPlaybackContext": {"html5Preference": "HTML5_PREF_WANTS"}}
        });
        let mut req = self
            .http
            .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", c.ua)
            .header("X-YouTube-Client-Name", c.id.to_string())
            .header("X-YouTube-Client-Version", c.version)
            .header("Origin", "https://www.youtube.com")
            .json(&body);
        if let Some(v) = visitor {
            req = req.header("X-Goog-Visitor-Id", v);
        }
        let resp = req.send().await.map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Err(format!("HTTP {}", resp.status().as_u16()));
        }
        let v: Value = resp.json().await.map_err(|e| e.to_string())?;
        pick_stream(&v, c.ua, c.key, prefer_mp4)
    }
}

pub fn pick_stream(v: &Value, ua: &str, client: &str, prefer_mp4: bool) -> Result<StreamInfo, String> {
    let status = v.pointer("/playabilityStatus/status").and_then(|s| s.as_str()).unwrap_or("");
    if status != "OK" {
        let reason = v.pointer("/playabilityStatus/reason").and_then(|s| s.as_str()).unwrap_or("");
        return Err(format!("{status} {reason}"));
    }
    let loud = v
        .pointer("/playerConfig/audioConfig/loudnessDb")
        .and_then(|x| x.as_f64())
        .map(|x| x as f32);
    let sd = v.get("streamingData").ok_or("no streamingData")?;
    let mut best: Option<(i64, &Value)> = None;
    if let Some(fs) = sd.get("adaptiveFormats").and_then(|f| f.as_array()) {
        for f in fs {
            let mime = f.get("mimeType").and_then(|m| m.as_str()).unwrap_or("");
            if !mime.starts_with("audio/") || f.get("url").is_none() {
                continue;
            }
            // Skip DRC / dubbed duplicate tracks when there is a default.
            if f.get("isDrc").and_then(|x| x.as_bool()).unwrap_or(false) {
                continue;
            }
            let br = f.get("bitrate").and_then(|b| b.as_i64()).unwrap_or(0);
            let mp4 = mime.starts_with("audio/mp4");
            // HE-AAC (mp4a.40.5 / .29, SBR) makes AVPlayer report a doubled duration: AAC-LC (itag 140) first.
            let he = mime.contains("40.5") || mime.contains("40.29");
            let score = if prefer_mp4 {
                if mp4 { (if he { 500_000 } else { 1_000_000 }) + br } else { -1 }
            } else if mime.contains("opus") {
                2_000_000 + br
            } else {
                br
            };
            if score < 0 {
                continue;
            }
            if best.map(|(s, _)| score > s).unwrap_or(true) {
                best = Some((score, f));
            }
        }
    }
    if let Some((_, f)) = best {
        let url = f["url"].as_str().unwrap_or("").to_string();
        let exp = url::Url::parse(&url)
            .ok()
            .and_then(|u| u.query_pairs().find(|(k, _)| k == "expire").and_then(|(_, v)| v.parse().ok()))
            .unwrap_or(now() + 5 * 3600);
        return Ok(StreamInfo {
            url,
            mime: f["mimeType"].as_str().unwrap_or("").to_string(),
            bitrate: f["bitrate"].as_u64().unwrap_or(0) as u32,
            content_length: f["contentLength"].as_str().and_then(|s| s.parse().ok()),
            user_agent: ua.to_string(),
            expires_at: exp,
            is_hls: false,
            loudness_db: loud,
            client: client.to_string(),
            duration_ms: f.get("approxDurationMs").and_then(|x| x.as_str()).and_then(|x| x.parse().ok())
                .or_else(|| v.pointer("/videoDetails/lengthSeconds").and_then(|x| x.as_str()).and_then(|x| x.parse::<u64>().ok()).map(|x| x * 1000))
                .unwrap_or(0),
        });
    }
    if let Some(h) = sd.get("hlsManifestUrl").and_then(|h| h.as_str()) {
        return Ok(StreamInfo {
            url: h.to_string(),
            mime: "application/x-mpegURL".into(),
            bitrate: 0,
            content_length: None,
            user_agent: ua.to_string(),
            expires_at: now() + 5 * 3600,
            is_hls: true,
            loudness_db: loud,
            client: client.to_string(),
            duration_ms: v.pointer("/videoDetails/lengthSeconds").and_then(|x| x.as_str()).and_then(|x| x.parse::<u64>().ok()).map(|x| x * 1000).unwrap_or(0),
        });
    }
    Err("no playable audio format".into())
}

fn net(e: rustypipe::error::Error) -> CoreError {
    CoreError::Network { msg: e.to_string() }
}

// ---------------------------------------------------------------------------------------------------------------
// YouTube Music home feed (browse FEmusic_home over InnerTube WEB_REMIX). Works signed out (visitor id), so a new
// listener sees Quick picks, mixes, new releases and charts from the first launch.

const WEB_REMIX_VERSION: &str = "1.20251015.03.00";
const WEB_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

fn runs_text(v: Option<&Value>) -> String {
    let Some(v) = v else { return String::new() };
    if let Some(s) = v.get("simpleText").and_then(|x| x.as_str()) {
        return s.to_string();
    }
    v.get("runs")
        .and_then(|r| r.as_array())
        .map(|a| a.iter().filter_map(|x| x.get("text").and_then(|t| t.as_str())).collect::<String>())
        .unwrap_or_default()
}

fn thumb_of(v: &Value) -> String {
    let mut best = String::new();
    let mut bw = 0u64;
    fn walk(v: &Value, best: &mut String, bw: &mut u64, d: u32) {
        if d > 8 {
            return;
        }
        match v {
            Value::Object(o) => {
                if let Some(arr) = o.get("thumbnails").and_then(|x| x.as_array()) {
                    for t in arr {
                        let w = t.get("width").and_then(|x| x.as_u64()).unwrap_or(0);
                        if let Some(u) = t.get("url").and_then(|x| x.as_str()) {
                            if w >= *bw {
                                *bw = w;
                                *best = u.to_string();
                            }
                        }
                    }
                    return;
                }
                for x in o.values() {
                    walk(x, best, bw, d + 1);
                }
            }
            Value::Array(a) => a.iter().for_each(|x| walk(x, best, bw, d + 1)),
            _ => {}
        }
    }
    walk(v, &mut best, &mut bw, 0);
    if best.starts_with("//") {
        best = format!("https:{best}");
    }
    if best.contains("googleusercontent") || best.contains("ggpht") {
        if let Some(i) = best.rfind("=w").or_else(|| best.rfind("=s")) {
            best.truncate(i);
            best.push_str("=w544-h544-l90-rj");
        }
    }
    best
}

fn parse_duration(s: &str) -> u32 {
    let mut t = 0u32;
    for p in s.trim().split(':') {
        match p.parse::<u32>() {
            Ok(n) => t = t * 60 + n,
            Err(_) => return 0,
        }
    }
    t
}

fn browse_id_of(v: &Value) -> Option<(String, String)> {
    let be = v.get("browseEndpoint")?;
    let id = be.get("browseId")?.as_str()?.to_string();
    let pt = be
        .pointer("/browseEndpointContextSupportedConfigs/browseEndpointContextMusicConfig/pageType")
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .to_string();
    Some((id, pt))
}

/// One item of a responsive list (songs: Quick picks, charts).
fn parse_list_item(r: &Value) -> Option<HomeItem> {
    let cols = r.get("flexColumns")?.as_array()?;
    let col = |i: usize| cols.get(i).and_then(|c| c.get("musicResponsiveListItemFlexColumnRenderer")).and_then(|c| c.get("text"));
    let title = runs_text(col(0));
    let vid = r
        .pointer("/playlistItemData/videoId")
        .or_else(|| col(0).and_then(|t| t.pointer("/runs/0/navigationEndpoint/watchEndpoint/videoId")))
        .or_else(|| r.pointer("/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId"))
        .and_then(|x| x.as_str())?
        .to_string();
    let mut artists: Vec<String> = Vec::new();
    let mut artist_id = None;
    let mut album = None;
    let mut album_id = None;
    let mut duration = 0u32;
    for i in 1..cols.len() {
        if let Some(runs) = col(i).and_then(|t| t.get("runs")).and_then(|r| r.as_array()) {
            for run in runs {
                let txt = run.get("text").and_then(|x| x.as_str()).unwrap_or("");
                match run.get("navigationEndpoint").and_then(browse_id_of) {
                    Some((id, pt)) if pt.contains("ARTIST") || pt.contains("USER_CHANNEL") => {
                        artists.push(txt.to_string());
                        artist_id.get_or_insert(id);
                    }
                    Some((id, pt)) if pt.contains("ALBUM") => {
                        album = Some(txt.to_string());
                        album_id = Some(id);
                    }
                    _ => {
                        if txt.contains(':') && parse_duration(txt) > 0 {
                            duration = parse_duration(txt);
                        }
                    }
                }
            }
            if artists.is_empty() && i == 1 {
                // no links: "Artist • 1.2M plays"
                let plain = runs_text(col(1));
                if let Some(a) = plain.split(" • ").next() {
                    if !a.is_empty() {
                        artists.push(a.to_string());
                    }
                }
            }
        }
    }
    if let Some(d) = r.pointer("/fixedColumns/0/musicResponsiveListItemFixedColumnRenderer/text") {
        let d = parse_duration(&runs_text(Some(d)));
        if d > 0 {
            duration = d;
        }
    }
    let thumb = r.get("thumbnail").map(thumb_of).unwrap_or_default();
    let artists_s = artists.join(", ");
    let track = Track { id: vid.clone(), title: title.clone(), artists: artists_s.clone(), artist_id, album, album_id, duration_s: duration, thumb: thumb.clone() };
    Some(HomeItem { kind: "song".into(), id: vid, title, subtitle: artists_s, thumb, track: Some(track) })
}

/// One card (album / playlist / artist / video / mix).
fn parse_two_row(r: &Value) -> Option<HomeItem> {
    let title = runs_text(r.get("title"));
    let subtitle = runs_text(r.get("subtitle"));
    let thumb = r.get("thumbnailRenderer").map(thumb_of).unwrap_or_default();
    let nav = r.get("navigationEndpoint")?;
    if let Some(w) = nav.get("watchEndpoint") {
        let vid = w.get("videoId")?.as_str()?.to_string();
        let mut artist_id = None;
        let mut artists = Vec::new();
        if let Some(runs) = r.pointer("/subtitle/runs").and_then(|x| x.as_array()) {
            for run in runs {
                if let Some((id, pt)) = run.get("navigationEndpoint").and_then(browse_id_of) {
                    if pt.contains("ARTIST") || pt.contains("USER_CHANNEL") {
                        artists.push(run.get("text").and_then(|x| x.as_str()).unwrap_or("").to_string());
                        artist_id.get_or_insert(id);
                    }
                }
            }
        }
        let a = if artists.is_empty() { subtitle.split(" • ").last().unwrap_or("").to_string() } else { artists.join(", ") };
        let track = Track { id: vid.clone(), title: title.clone(), artists: a.clone(), artist_id, album: None, album_id: None, duration_s: 0, thumb: thumb.clone() };
        return Some(HomeItem { kind: "video".into(), id: vid, title, subtitle, thumb, track: Some(track) });
    }
    if let Some(w) = nav.get("watchPlaylistEndpoint") {
        let pid = w.get("playlistId")?.as_str()?.to_string();
        return Some(HomeItem { kind: "playlist".into(), id: pid, title, subtitle, thumb, track: None });
    }
    let (id, pt) = browse_id_of(nav)?;
    let kind = if pt.contains("ALBUM") {
        "album"
    } else if pt.contains("ARTIST") || pt.contains("USER_CHANNEL") {
        "artist"
    } else if pt.contains("PLAYLIST") {
        "playlist"
    } else {
        return None; // podcasts, episodes, moods...
    };
    let id = if kind == "playlist" { id.strip_prefix("VL").map(|x| x.to_string()).unwrap_or(id) } else { id };
    Some(HomeItem { kind: kind.into(), id, title, subtitle, thumb, track: None })
}

pub fn parse_home_shelves(v: &Value, out: &mut Vec<HomeShelf>) {
    fn walk(v: &Value, out: &mut Vec<HomeShelf>, d: u32) {
        if d > 24 {
            return;
        }
        match v {
            Value::Object(o) => {
                if let Some(sh) = o.get("musicCarouselShelfRenderer") {
                    let title = runs_text(sh.pointer("/header/musicCarouselShelfBasicHeaderRenderer/title"));
                    let low = title.to_lowercase();
                    if low.contains("podcast") || low.contains("episode") {
                        return;
                    }
                    let mut items = Vec::new();
                    let mut songs = 0;
                    for c in sh.get("contents").and_then(|x| x.as_array()).into_iter().flatten() {
                        if let Some(r) = c.get("musicResponsiveListItemRenderer") {
                            if let Some(it) = parse_list_item(r) {
                                songs += 1;
                                items.push(it);
                            }
                        } else if let Some(r) = c.get("musicTwoRowItemRenderer") {
                            if let Some(it) = parse_two_row(r) {
                                items.push(it);
                            }
                        }
                    }
                    if !items.is_empty() && !title.is_empty() {
                        let layout = if songs * 2 >= items.len() { "songs" } else { "cards" };
                        out.push(HomeShelf { title, layout: layout.into(), items });
                    }
                    return;
                }
                for x in o.values() {
                    walk(x, out, d + 1);
                }
            }
            Value::Array(a) => a.iter().for_each(|x| walk(x, out, d + 1)),
            _ => {}
        }
    }
    walk(v, out, 0);
}

fn first_continuation(v: &Value) -> Option<String> {
    fn walk(v: &Value, d: u32) -> Option<String> {
        if d > 24 {
            return None;
        }
        match v {
            Value::Object(o) => {
                if let Some(c) = o.get("nextContinuationData").and_then(|x| x.get("continuation")).and_then(|x| x.as_str()) {
                    return Some(c.to_string());
                }
                o.values().find_map(|x| walk(x, d + 1))
            }
            Value::Array(a) => a.iter().find_map(|x| walk(x, d + 1)),
            _ => None,
        }
    }
    walk(v, 0)
}

impl Yt {
    async fn remix_post(&self, endpoint: &str, query: &str, body: Value, visitor: Option<&str>, cookie: Option<&str>) -> Result<Value, CoreError> {
        let mut ctx = json!({"client": {"clientName": "WEB_REMIX", "clientVersion": WEB_REMIX_VERSION, "hl": "en", "gl": "IN"}});
        if let Some(v) = visitor {
            ctx["client"]["visitorData"] = json!(v);
        }
        let mut b = body;
        b["context"] = ctx;
        let mut req = self
            .http
            .post(format!("https://music.youtube.com/youtubei/v1/{endpoint}?prettyPrint=false{query}"))
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://music.youtube.com")
            .header("X-Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("Content-Type", "application/json");
        if let Some(v) = visitor {
            req = req.header("X-Goog-Visitor-Id", v);
        }
        if let Some(c) = cookie {
            if let Some(h) = sapisid_hash(c) {
                req = req.header("Cookie", c).header("Authorization", h).header("X-Goog-AuthUser", "0");
            }
        }
        let r = req.json(&b).send().await.map_err(|e| CoreError::Network { msg: e.to_string() })?;
        if !r.status().is_success() {
            return Err(CoreError::Network { msg: format!("HTTP {} from {endpoint}", r.status().as_u16()) });
        }
        r.json::<Value>().await.map_err(|e| CoreError::Network { msg: e.to_string() })
    }

    /// The YouTube Music home page: first page plus up to `pages` continuations.
    pub async fn home_shelves(&self, pages: u32, cookie: Option<&str>) -> Result<Vec<HomeShelf>, CoreError> {
        let first = self.remix_post("browse", "", json!({"browseId": "FEmusic_home"}), None, cookie).await?;
        let visitor = first.pointer("/responseContext/visitorData").and_then(|x| x.as_str()).map(|s| s.to_string());
        let mut out = Vec::new();
        parse_home_shelves(&first, &mut out);
        let mut cont = first_continuation(&first);
        let mut n = 0;
        while let Some(c) = cont.take() {
            if n >= pages {
                break;
            }
            n += 1;
            let enc = urlencoding::encode(&c).to_string();
            let q = format!("&ctoken={enc}&continuation={enc}&type=next");
            match self.remix_post("browse", &q, json!({}), visitor.as_deref(), cookie).await {
                Ok(v) => {
                    parse_home_shelves(&v, &mut out);
                    cont = first_continuation(&v);
                }
                Err(_) => break,
            }
        }
        if out.is_empty() {
            return Err(CoreError::Unavailable { msg: "Home feed came back empty".into() });
        }
        Ok(out)
    }

    /// Name, handle and avatar of the signed-in Google account (needs the sign-in cookie).
    pub async fn account_info(&self, cookie: &str) -> Result<AccountInfo, CoreError> {
        let v = self.remix_post("account/account_menu", "", json!({}), None, Some(cookie)).await?;
        let h = find_key(&v, "activeAccountHeaderRenderer").ok_or(CoreError::Unavailable { msg: "Not signed in".into() })?;
        Ok(AccountInfo {
            name: runs_text(h.get("accountName")),
            handle: runs_text(h.get("channelHandle")),
            avatar: h.get("accountPhoto").map(thumb_of).unwrap_or_default(),
            email: runs_text(h.get("email")),
        })
    }
}

fn find_key<'a>(v: &'a Value, k: &str) -> Option<&'a Value> {
    match v {
        Value::Object(o) => o.get(k).or_else(|| o.values().find_map(|x| find_key(x, k))),
        Value::Array(a) => a.iter().find_map(|x| find_key(x, k)),
        _ => None,
    }
}

/// `SAPISIDHASH <ts>_<sha1(ts SAPISID origin)>` from a cookie header string.
pub fn sapisid_hash(cookie: &str) -> Option<String> {
    let sap = cookie.split(';').map(|p| p.trim()).find_map(|p| {
        p.strip_prefix("__Secure-3PAPISID=").or_else(|| p.strip_prefix("SAPISID="))
    })?;
    let ts = now();
    let mut h = sha1_smol::Sha1::new();
    h.update(format!("{ts} {sap} https://music.youtube.com").as_bytes());
    Some(format!("SAPISIDHASH {ts}_{}", h.digest()))
}

#[cfg(test)]
mod home_tests {
    use super::*;
    #[test]
    fn parses_saved_home() {
        let Ok(s) = std::fs::read_to_string(std::env::var("HOME_JSON").unwrap_or_default()) else { return };
        let v: Value = serde_json::from_str(&s).unwrap();
        let mut out = Vec::new();
        parse_home_shelves(&v, &mut out);
        assert!(!out.is_empty());
        for sh in &out {
            eprintln!("{} [{}] {}: {:?}", sh.title, sh.layout, sh.items.len(), sh.items.first().map(|i| (&i.kind, &i.title, &i.subtitle, &i.thumb)));
        }
    }
}

#[cfg(test)]
mod home_live {
    #[tokio::test]
    async fn live_home() {
        if std::env::var("LIVE").is_err() {
            return;
        }
        let d = tempfile::tempdir().unwrap();
        let yt = super::Yt::new(d.path().to_str().unwrap()).unwrap();
        let s = yt.home_shelves(2, None).await.unwrap();
        for sh in &s {
            eprintln!("LIVE {} [{}] {}", sh.title, sh.layout, sh.items.len());
        }
        assert!(s.len() >= 3);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Up next: YouTube Music watch-next automix (playlist RDAMVM<id>, music-filtered), like the YTM / Spotify radio.

/// Candidate from the watch-next panel, with the music video type (ATV = official audio, OMV = official video,
/// UGC = user upload, PODCAST_EPISODE...).
pub struct NextItem {
    pub track: Track,
    pub kind: String,
}

pub fn parse_next_panel(v: &Value) -> Vec<NextItem> {
    let mut out = Vec::new();
    fn walk(v: &Value, out: &mut Vec<NextItem>, d: u32) {
        if d > 30 {
            return;
        }
        match v {
            Value::Object(o) => {
                if let Some(r) = o.get("playlistPanelVideoRenderer") {
                    if let Some(it) = parse_panel_video(r) {
                        out.push(it);
                    }
                    return;
                }
                for x in o.values() {
                    walk(x, out, d + 1);
                }
            }
            Value::Array(a) => a.iter().for_each(|x| walk(x, out, d + 1)),
            _ => {}
        }
    }
    walk(v, &mut out, 0);
    out
}

fn parse_panel_video(r: &Value) -> Option<NextItem> {
    let id = r.get("videoId")?.as_str()?.to_string();
    let title = runs_text(r.get("title"));
    let mut artists = Vec::new();
    let mut artist_id = None;
    let mut album = None;
    let mut album_id = None;
    let byline = r.get("longBylineText").or_else(|| r.get("shortBylineText"));
    if let Some(runs) = byline.and_then(|b| b.get("runs")).and_then(|x| x.as_array()) {
        for run in runs {
            let txt = run.get("text").and_then(|x| x.as_str()).unwrap_or("");
            match run.get("navigationEndpoint").and_then(browse_id_of) {
                Some((bid, pt)) if pt.contains("ALBUM") => {
                    album = Some(txt.to_string());
                    album_id = Some(bid);
                }
                Some((bid, _)) => {
                    artists.push(txt.to_string());
                    artist_id.get_or_insert(bid);
                }
                None => {}
            }
        }
        if artists.is_empty() {
            if let Some(a) = runs.first().and_then(|x| x.get("text")).and_then(|x| x.as_str()) {
                artists.push(a.to_string());
            }
        }
    }
    let kind = r
        .pointer("/navigationEndpoint/watchEndpoint/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType")
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .trim_start_matches("MUSIC_VIDEO_TYPE_")
        .to_string();
    let duration_s = parse_duration(&runs_text(r.get("lengthText")));
    let thumb = r.get("thumbnail").map(thumb_of).unwrap_or_default();
    Some(NextItem {
        track: Track { id, title, artists: artists.join(", "), artist_id, album, album_id, duration_s, thumb },
        kind,
    })
}

/// Words that mark a different recording of a song (or a non-music upload).
const OTHER_VERSION: &[&str] = &[
    "cover", "live", "karaoke", "slowed", "sped up", "speed up", "8d", "nightcore", "reverb", "instrumental",
    "the voice", "audition", "x factor", "idol", "got talent", "reaction", "tutorial", "lyrics", "lyric video",
    "remix", "acoustic version", "piano version", "mashup", "bass boosted", "1 hour", "loop",
];

pub fn is_other_version_of(seed: &Track, t: &Track) -> bool {
    let a = crate::import::norm(&seed.title);
    let b = crate::import::norm(&t.title);
    if a.is_empty() || b.is_empty() {
        return false;
    }
    if t.id == seed.id {
        return true;
    }
    a == b || (a.len() >= 6 && b.contains(&a)) || (b.len() >= 6 && a.contains(&b))
}

pub fn looks_like_non_original(t: &Track) -> bool {
    let low = format!(" {} ", t.title.to_lowercase());
    OTHER_VERSION.iter().any(|w| low.contains(&format!(" {w}")) || low.contains(&format!("({w}")) || low.contains(&format!("[{w}")))
}

impl Yt {
    /// Watch-next automix for a track, music only, official tracks first.
    pub async fn watch_next(&self, video_id: &str) -> Result<Vec<NextItem>, CoreError> {
        let body = json!({"videoId": video_id, "playlistId": format!("RDAMVM{video_id}"), "params": "wAEB", "isAudioOnly": true, "enablePersistentPlaylistPanel": true});
        let v = self.remix_post("next", "", body, None, None).await?;
        let items = parse_next_panel(&v);
        if items.is_empty() {
            return Err(CoreError::Unavailable { msg: "no up next".into() });
        }
        Ok(items)
    }
}

#[cfg(test)]
mod next_tests {
    use super::*;
    fn tr(id: &str, t: &str, a: &str) -> Track {
        Track { id: id.into(), title: t.into(), artists: a.into(), artist_id: None, album: None, album_id: None, duration_s: 200, thumb: String::new() }
    }
    #[test]
    fn versions() {
        let seed = tr("a", "Young And Beautiful", "Lana Del Rey");
        assert!(is_other_version_of(&seed, &tr("b", "Young and Beautiful (The Voice Norway)", "X")));
        assert!(is_other_version_of(&seed, &tr("c", "young and beautiful - slowed", "starclouds")));
        assert!(!is_other_version_of(&seed, &tr("d", "Summertime Sadness", "Lana Del Rey")));
        assert!(looks_like_non_original(&tr("e", "Video Games (Live at Coachella)", "Lana Del Rey")));
        assert!(!looks_like_non_original(&tr("f", "Born to Die", "Lana Del Rey")));
    }
    #[test]
    fn panel() {
        let j = serde_json::json!({"contents":{"x":[{"playlistPanelVideoRenderer":{"videoId":"v1","title":{"runs":[{"text":"Summertime Sadness"}]},
          "longBylineText":{"runs":[{"text":"Lana Del Rey","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":" • "},{"text":"Born To Die","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]},
          "lengthText":{"runs":[{"text":"4:25"}]},
          "navigationEndpoint":{"watchEndpoint":{"videoId":"v1","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}}}}]}});
        let v = parse_next_panel(&j);
        assert_eq!(v.len(), 1);
        assert_eq!(v[0].kind, "ATV");
        assert_eq!(v[0].track.artists, "Lana Del Rey");
        assert_eq!(v[0].track.album.as_deref(), Some("Born To Die"));
        assert_eq!(v[0].track.duration_s, 265);
    }
}
