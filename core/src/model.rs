//! Plain records shared with Kotlin and Swift.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Track {
    /// Video id, or `local:<uri>` for a file on the device.
    pub id: String,
    pub title: String,
    pub artists: String,
    pub artist_id: Option<String>,
    pub album: Option<String>,
    pub album_id: Option<String>,
    pub duration_s: u32,
    pub thumb: String,
}

impl Track {
    pub fn is_local(&self) -> bool {
        self.id.starts_with("local:")
    }
    pub fn main_artist(&self) -> String {
        self.artists
            .split(&[',', '&'][..])
            .next()
            .unwrap_or("")
            .trim()
            .to_string()
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Artist {
    pub id: String,
    pub name: String,
    pub thumb: String,
    pub subtitle: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Album {
    pub id: String,
    pub title: String,
    pub artists: String,
    pub year: Option<u16>,
    pub thumb: String,
    pub kind: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Playlist {
    pub id: String,
    pub title: String,
    pub subtitle: String,
    pub thumb: String,
    pub track_count: Option<u64>,
    /// "yt", "local", "import"
    pub source: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchResults {
    pub top: Option<Track>,
    pub tracks: Vec<Track>,
    pub artists: Vec<Artist>,
    pub albums: Vec<Album>,
    pub playlists: Vec<Playlist>,
    pub corrected_query: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AlbumPage {
    pub album: Album,
    pub tracks: Vec<Track>,
    pub description: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ArtistPage {
    pub artist: Artist,
    pub description: Option<String>,
    pub top_tracks: Vec<Track>,
    pub albums: Vec<Album>,
    pub similar: Vec<Artist>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PlaylistPage {
    pub playlist: Playlist,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Mix {
    pub id: String,
    pub title: String,
    pub subtitle: String,
    pub thumb: String,
    pub seed_track_id: Option<String>,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct HomeFeed {
    pub greeting: String,
    pub mixes: Vec<Mix>,
    pub recent: Vec<Track>,
    pub trending: Vec<Track>,
    pub suggestions: Vec<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StreamInfo {
    pub url: String,
    pub mime: String,
    pub bitrate: u32,
    pub content_length: Option<u64>,
    pub user_agent: String,
    /// Unix seconds when the URL stops working.
    pub expires_at: i64,
    pub is_hls: bool,
    pub loudness_db: Option<f32>,
    pub client: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LyricWord {
    pub text: String,
    pub start_ms: u32,
    pub end_ms: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LyricLine {
    pub start_ms: u32,
    pub end_ms: u32,
    pub text: String,
    pub words: Vec<LyricWord>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Lyrics {
    pub synced: bool,
    pub lines: Vec<LyricLine>,
    pub source: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct NamedCount {
    pub id: String,
    pub name: String,
    pub thumb: String,
    pub plays: u32,
    pub minutes: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GenreShare {
    pub name: String,
    pub percent: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StatsSummary {
    pub range: String,
    pub minutes: u32,
    pub plays: u32,
    pub prev_minutes: u32,
    pub streak_days: u32,
    pub best_streak_days: u32,
    pub top_artists: Vec<NamedCount>,
    pub top_tracks: Vec<NamedCount>,
    /// 24 buckets of minutes by local hour.
    pub clock: Vec<u32>,
    /// Minutes per bucket for the bar chart (days for week/month, months for year/all).
    pub series: Vec<u32>,
    pub genres: Vec<GenreShare>,
    pub streak_week: Vec<bool>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct QueueState {
    pub tracks: Vec<Track>,
    pub index: i32,
    pub shuffle: bool,
    /// 0 off, 1 all, 2 one
    pub repeat: u8,
    pub autoplay: bool,
    /// Index where the autoplay ("from radio") part starts, -1 when none.
    pub autoplay_from: i32,
    pub source_title: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImportedTrack {
    pub title: String,
    pub artists: String,
    pub duration_s: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImportMatch {
    pub source: ImportedTrack,
    pub matched: Option<Track>,
    /// 0..100
    pub confidence: u32,
    /// "matched", "review", "missing"
    pub status: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImportResult {
    pub name: String,
    pub service: String,
    pub cover: String,
    pub matches: Vec<ImportMatch>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct IsaPlan {
    pub title: String,
    pub moods: Vec<String>,
    pub languages: Vec<String>,
    pub seeds: Vec<String>,
    pub avoid_vocals: bool,
    pub max_duration_s: Option<u32>,
    pub energy: i32,
    pub explanation: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct IsaResult {
    pub plan: IsaPlan,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DownloadItem {
    pub track: Track,
    pub path: String,
    pub bytes: u64,
    pub created_at: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReleaseAsset {
    pub name: String,
    pub api_url: String,
    pub browser_url: String,
    pub size: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct UpdateInfo {
    pub version: String,
    pub tag: String,
    pub notes: String,
    pub newer: bool,
    pub assets: Vec<ReleaseAsset>,
    pub published_at: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LibraryCounts {
    pub liked: u32,
    pub playlists: u32,
    pub albums: u32,
    pub artists: u32,
    pub downloads: u32,
    pub local: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StreamingHealth {
    pub working_client: String,
    pub last_error: String,
    pub last_ok_at: i64,
    pub last_error_at: i64,
    pub failures: u32,
    pub clients: Vec<String>,
    pub signed_in: bool,
    pub cache_bytes: u64,
    pub cache_limit_mb: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncResult {
    pub liked: u32,
    pub playlists: u32,
}
