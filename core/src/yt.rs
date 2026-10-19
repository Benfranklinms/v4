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
        if let Some((v, t)) = self.visitor.lock().unwrap().clone() {
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
        *self.visitor.lock().unwrap() = Some((vd.clone(), now()));
        Some(vd)
    }

    /// Resolve a playable audio stream. `prefer_mp4` picks AAC/m4a (AVPlayer), otherwise the best of Opus/AAC.
    /// Clients are tried starting with the one that last worked; `skip_client` skips a client that just failed
    /// at playback (403 / throttled) so the retry goes to the next one.
    pub async fn stream(&self, video_id: &str, prefer_mp4: bool, skip_client: Option<&str>) -> Result<StreamInfo, CoreError> {
        let visitor = self.visitor_data().await;
        let pref = self.health.lock().unwrap().preferred;
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
                    let mut h = self.health.lock().unwrap();
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
                        let mut h = self.health.lock().unwrap();
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
                        });
                    }
                    errs.push("rustypipe: no audio".into());
                }
                Err(e) => errs.push(format!("rustypipe: {e}")),
            }
        }
        let msg = errs.join("; ");
        let mut h = self.health.lock().unwrap();
        h.last_error = msg.clone();
        h.last_error_at = now();
        h.failures += 1;
        Err(CoreError::Unavailable { msg })
    }

    /// Playback of a resolved URL failed (403, throttling): demote that client.
    pub fn report_client_failure(&self, client: &str, error: &str) {
        let mut h = self.health.lock().unwrap();
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
            let score = if prefer_mp4 {
                if mp4 { 1_000_000 + br } else { -1 }
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
        });
    }
    Err("no playable audio format".into())
}

fn net(e: rustypipe::error::Error) -> CoreError {
    CoreError::Network { msg: e.to_string() }
}
