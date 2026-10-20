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

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct AlbumPage {
    pub album: Album,
    pub tracks: Vec<Track>,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct ArtistPage {
    pub artist: Artist,
    pub description: Option<String>,
    pub top_tracks: Vec<Track>,
    pub albums: Vec<Album>,
    pub similar: Vec<Artist>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct PlaylistPage {
    pub playlist: Playlist,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Mix {
    pub id: String,
    pub title: String,
    pub subtitle: String,
    pub thumb: String,
    pub seed_track_id: Option<String>,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct HomeFeed {
    pub greeting: String,
    pub mixes: Vec<Mix>,
    pub recent: Vec<Track>,
    pub trending: Vec<Track>,
    pub suggestions: Vec<String>,
    /// YouTube Music home shelves (Quick picks, mixes, new releases, charts...), in page order.
    pub shelves: Vec<HomeShelf>,
    /// Taste engine: the listener's most repeated tracks of the last 30 days.
    pub on_repeat: Vec<Track>,
    /// Taste engine: never-played tracks from the related graph of the listener's top artists.
    pub discover: Vec<Track>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct HomeShelf {
    pub title: String,
    /// "songs" (list of tracks, e.g. Quick picks) or "cards" (albums / playlists / artists / videos)
    pub layout: String,
    pub items: Vec<HomeItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct HomeItem {
    /// "song", "video", "album", "playlist", "artist"
    pub kind: String,
    pub id: String,
    pub title: String,
    pub subtitle: String,
    pub thumb: String,
    pub track: Option<Track>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AccountInfo {
    pub name: String,
    pub handle: String,
    pub avatar: String,
    pub email: String,
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
    /// Real length of the resolved stream (0 = unknown). UIs show this instead of player-reported durations.
    pub duration_ms: u64,
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

#[derive(Debug, Clone, Default, uniffi::Record)]
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

/// Last playback session (queue + position), restored after a relaunch.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Session {
    pub tracks: Vec<Track>,
    pub index: i32,
    pub position_ms: u64,
    pub shuffle: bool,
    /// 0 off, 1 all, 2 one
    pub repeat: u8,
    pub autoplay_from: i32,
    pub source_title: String,
    /// Unix seconds of the last save.
    pub saved_at: i64,
}

/// More tracks of a playlist (see `Core::playlist_more`).
#[derive(Debug, Clone, uniffi::Record)]
pub struct PlaylistMore {
    pub tracks: Vec<Track>,
    /// No more pages.
    pub done: bool,
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
    /// Other candidates (best first) for the review / swap screen.
    pub alternatives: Vec<Track>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImportResult {
    pub name: String,
    pub service: String,
    pub cover: String,
    pub matches: Vec<ImportMatch>,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
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

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct LibraryCounts {
    pub liked: u32,
    pub playlists: u32,
    pub albums: u32,
    pub artists: u32,
    pub downloads: u32,
    pub local: u32,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
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

/// Live progress of a playlist import (matching runs in the core; UIs poll this).
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct ImportProgress {
    pub running: bool,
    pub done: u32,
    pub total: u32,
    pub current: String,
}

// ---------------------------------------------------------------- Stats page (0.2.8)

#[derive(Debug, Clone, uniffi::Record)]
pub struct TrackStat {
    pub track: Track,
    pub plays: u32,
    pub minutes: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AlbumStat {
    /// Album name as played (grouping key is the lowercased name).
    pub name: String,
    pub artist: String,
    /// Empty when no play carried an album id: resolve with `stats_album_id` when tapped.
    pub album_id: String,
    pub thumb: String,
    pub plays: u32,
    pub minutes: u32,
}

/// One bar of the hero chart: a day (week/month) or a month (year/all).
#[derive(Debug, Clone, uniffi::Record)]
pub struct StatsBar {
    /// Short axis label: "W", "17", "O", "Oct".
    pub label: String,
    /// Long label for scrubbing: "Saturday 17 Oct", "October 2026".
    pub title: String,
    pub minutes: u32,
    pub plays: u32,
    /// "Mostly Illuminati, after 9 PM" ("" when nothing was played).
    pub line: String,
    pub top_track_id: String,
    /// Local hour with the most listening, -1 when empty.
    pub peak_hour: i32,
    /// Epoch seconds of the bucket start / end (exclusive).
    pub start: i64,
    pub end: i64,
    pub is_current: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PlaylistSlot {
    pub playlist_id: String,
    pub title: String,
    pub position: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SkippedTrack {
    pub track: Track,
    pub skips: u32,
    pub plays: u32,
    /// Your playlists that hold this track (for "Remove").
    pub playlists: Vec<PlaylistSlot>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GoalDay {
    /// "M", "T", ...
    pub label: String,
    pub minutes: u32,
    pub is_today: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StatsPage {
    pub range: String,
    /// "14 – 20 Oct", "October 2026", "Since 19 Oct".
    pub period_label: String,
    pub minutes: u32,
    pub prev_minutes: u32,
    /// false when the previous period has no history (no % chip).
    pub has_prev: bool,
    pub plays: u32,
    pub bars: Vec<StatsBar>,
    /// Same-position minutes of the previous period (ghost bars), empty for "all".
    pub prev_bars: Vec<u32>,
    /// Mean minutes over the bars that are not in the future.
    pub avg_minutes: u32,
    /// 24 buckets of minutes by local hour.
    pub clock: Vec<u32>,
    pub peak_hour: i32,
    /// "night" | "morning" | "afternoon" | "evening" | "mixed" | "" (no data)
    pub mood: String,
    /// "Mostly late nights this week".
    pub mood_line: String,
    pub top_tracks: Vec<TrackStat>,
    pub top_artists: Vec<NamedCount>,
    pub top_albums: Vec<AlbumStat>,
    /// Artists first played inside this range (all: last 30 days).
    pub new_artists: Vec<NamedCount>,
    /// false until there are ~14 days of history: "New to you" stays hidden.
    pub new_artists_ready: bool,
    pub skipped: Vec<SkippedTrack>,
    pub goal_minutes: u32,
    pub goal_week: Vec<GoalDay>,
    pub goal_streak: u32,
    pub goal_best: u32,
    pub today_minutes: u32,
    /// Distinct local days with any listening (all time) and days since the first play (1 = today only).
    pub active_days: u32,
    pub history_days: u32,
    pub first_play_at: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct HourDetail {
    pub hour: u32,
    /// "Around 11 PM"
    pub title: String,
    pub minutes: u32,
    pub tracks: Vec<TrackStat>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PlayEntry {
    pub track: Track,
    pub started_at: i64,
    /// "9:41 PM" in the play's own local time.
    pub time: String,
    pub listened_s: u32,
    pub skipped: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Recap {
    pub range: String,
    /// "Your week" / "Your month"
    pub title: String,
    pub period_label: String,
    pub minutes: u32,
    pub plays: u32,
    pub change_percent: Option<i32>,
    pub top_artist: Option<NamedCount>,
    pub top_track: Option<TrackStat>,
    pub top_tracks: Vec<TrackStat>,
    pub top_artists: Vec<NamedCount>,
    pub mood_line: String,
    pub peak_hour: i32,
    pub goal_streak: u32,
    pub new_artists: u32,
}
