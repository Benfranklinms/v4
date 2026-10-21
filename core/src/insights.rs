//! Stats page (0.2.8): everything is derived from the local plays table (plus playlists and kv).
//! Pure functions over `PlayRow`s so they can be unit tested without a database.

use crate::model::*;
use std::collections::{HashMap, HashSet};

const DAY: i64 = 86_400;
pub const GOAL_KEY: &str = "stats_goal_min";
pub const DEFAULT_GOAL: u32 = 30;
/// "New to you" stays hidden until there is about two weeks of history.
pub const NEW_ARTISTS_MIN_DAYS: u32 = 14;

#[derive(Debug, Clone)]
pub struct PlayRow {
    pub track_id: String,
    pub title: String,
    pub artist: String,
    pub artist_id: Option<String>,
    pub album: Option<String>,
    pub thumb: String,
    pub duration_s: u32,
    pub at: i64,
    pub ms: i64,
    pub skipped: bool,
    pub tz: i32,
}

impl PlayRow {
    pub fn local_day(&self) -> i64 {
        (self.at + self.tz as i64 * 60).div_euclid(DAY)
    }
    pub fn local_hour(&self) -> usize {
        ((self.at + self.tz as i64 * 60).rem_euclid(DAY) / 3600) as usize
    }
    pub fn track(&self) -> Track {
        Track {
            id: self.track_id.clone(),
            title: self.title.clone(),
            artists: self.artist.clone(),
            artist_id: self.artist_id.clone(),
            album: self.album.clone(),
            album_id: None,
            duration_s: self.duration_s,
            thumb: self.thumb.clone(),
        }
    }
    /// A play that counts: not skipped, or listened for 30 s or more.
    pub fn counts(&self) -> bool {
        !self.skipped || self.ms >= 30_000
    }
}

pub fn main_artist(a: &str) -> String {
    a.split(&[',', '&'][..]).next().unwrap_or("").trim().to_string()
}

/// One key per song for top lists: the same title (case-insensitive, trimmed) by the same artist is one
/// song even when it was played from two video ids ("Young And Beautiful" / "Young and Beautiful").
/// Falls back to the track id when there is no title.
pub fn song_key(track_id: &str, title: &str, artist: &str) -> String {
    let t = title.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    if t.is_empty() {
        return format!("id:{track_id}");
    }
    let a = main_artist(artist).split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    format!("{t}\u{1f}{a}")
}

/// Song group per row: rows of one video id stay together (even if its artist text changed between
/// plays), and different ids join when their song_key matches.
pub fn song_groups(rows: &[PlayRow]) -> Vec<String> {
    let mut by_id: HashMap<&str, String> = HashMap::new();
    let mut by_key: HashMap<String, String> = HashMap::new();
    rows.iter()
        .map(|r| {
            if let Some(g) = by_id.get(r.track_id.as_str()) {
                return g.clone();
            }
            let k = song_key(&r.track_id, &r.title, &r.artist);
            let g = by_key.entry(k).or_insert_with(|| r.track_id.clone()).clone();
            by_id.insert(r.track_id.as_str(), g.clone());
            g
        })
        .collect()
}

// ------------------------------------------------------------------ calendar helpers (no chrono)

/// days since 1970-01-01 -> (year, month 1..12, day 1..31)
pub fn civil(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

pub fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m as i64 + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// 0 = Sunday
pub fn weekday(z: i64) -> usize {
    (z + 4).rem_euclid(7) as usize
}

const WD_LETTER: [&str; 7] = ["S", "M", "T", "W", "T", "F", "S"];
const WD_NAME: [&str; 7] = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const MON_SHORT: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const MON_NAME: [&str; 12] =
    ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

fn month_index(z: i64) -> i64 {
    let (y, m, _) = civil(z);
    y * 12 + (m as i64 - 1)
}
fn month_start_day(mi: i64) -> i64 {
    days_from_civil(mi.div_euclid(12), (mi.rem_euclid(12) + 1) as u32, 1)
}

pub fn hour_label(h: u32) -> String {
    let h = h % 24;
    let (n, ap) = match h {
        0 => (12, "AM"),
        1..=11 => (h, "AM"),
        12 => (12, "PM"),
        _ => (h - 12, "PM"),
    };
    format!("{n} {ap}")
}

fn day_title(z: i64) -> String {
    let (_, m, d) = civil(z);
    format!("{} {} {}", WD_NAME[weekday(z)], d, MON_SHORT[m as usize - 1])
}

fn span_label(a: i64, b: i64) -> String {
    let (ya, ma, da) = civil(a);
    let (yb, mb, db) = civil(b);
    if ya == yb && ma == mb {
        format!("{da} – {db} {}", MON_SHORT[mb as usize - 1])
    } else if ya == yb {
        format!("{da} {} – {db} {}", MON_SHORT[ma as usize - 1], MON_SHORT[mb as usize - 1])
    } else {
        format!("{da} {} {ya} – {db} {} {yb}", MON_SHORT[ma as usize - 1], MON_SHORT[mb as usize - 1])
    }
}

// ------------------------------------------------------------------ buckets

#[derive(Debug, Clone, Copy, PartialEq)]
enum Unit {
    Day,
    Month,
    Year,
}

/// The bars of a range: `keys[i]` is a local day (Day), a month index (Month) or a year (Year).
struct Layout {
    unit: Unit,
    keys: Vec<i64>,
    prev_keys: Vec<i64>,
    /// First local day of the range (inclusive) and of the previous period.
    start_day: i64,
    prev_start_day: i64,
}

fn key_of(unit: Unit, day: i64) -> i64 {
    match unit {
        Unit::Day => day,
        Unit::Month => month_index(day),
        Unit::Year => civil(day).0,
    }
}

fn layout(range: &str, today: i64, first_day: Option<i64>) -> Layout {
    match range {
        "week" | "month" => {
            let n = if range == "week" { 7 } else { 30 };
            let keys: Vec<i64> = (0..n).map(|i| today - (n - 1) + i).collect();
            let prev_keys = keys.iter().map(|d| d - n).collect();
            Layout { unit: Unit::Day, start_day: today - (n - 1), prev_start_day: today - (2 * n - 1), keys, prev_keys }
        }
        "year" => {
            let cur = month_index(today);
            let keys: Vec<i64> = (0..12).map(|i| cur - 11 + i).collect();
            let prev_keys = keys.iter().map(|k| k - 12).collect();
            Layout {
                unit: Unit::Month,
                start_day: month_start_day(cur - 11),
                prev_start_day: month_start_day(cur - 23),
                keys,
                prev_keys,
            }
        }
        _ => {
            let first = first_day.unwrap_or(today).min(today);
            let (m0, m1) = (month_index(first), month_index(today));
            if m1 - m0 >= 24 {
                let (y0, y1) = (civil(first).0, civil(today).0);
                Layout { unit: Unit::Year, keys: (y0..=y1).collect(), prev_keys: vec![], start_day: first, prev_start_day: first }
            } else {
                Layout { unit: Unit::Month, keys: (m0..=m1).collect(), prev_keys: vec![], start_day: first, prev_start_day: first }
            }
        }
    }
}

fn bar_labels(unit: Unit, key: i64) -> (String, String, i64, i64) {
    // (label, title, start day, end day exclusive)
    match unit {
        Unit::Day => (WD_LETTER[weekday(key)].to_string(), day_title(key), key, key + 1),
        Unit::Month => {
            let m = key.rem_euclid(12) as usize;
            (MON_SHORT[m].to_string(), format!("{} {}", MON_NAME[m], key.div_euclid(12)), month_start_day(key), month_start_day(key + 1))
        }
        Unit::Year => (format!("{key}"), format!("{key}"), days_from_civil(key, 1, 1), days_from_civil(key + 1, 1, 1)),
    }
}

#[derive(Default)]
struct Acc {
    ms: i64,
    plays: u32,
    tracks: HashMap<String, i64>,
    titles: HashMap<String, String>,
    ids: HashMap<String, String>,
    hours: [i64; 24],
}

fn top_of(m: &HashMap<String, i64>) -> Option<(&String, i64)> {
    m.iter().map(|(k, v)| (k, *v)).max_by(|a, b| a.1.cmp(&b.1).then(b.0.cmp(a.0)))
}

fn peak(h: &[i64]) -> i32 {
    let mut best = -1i32;
    let mut bv = 0i64;
    for (i, v) in h.iter().enumerate() {
        if *v > bv {
            bv = *v;
            best = i as i32;
        }
    }
    best
}

pub fn mood_of(clock_ms: &[i64]) -> &'static str {
    let total: i64 = clock_ms.iter().sum();
    if total < 60_000 {
        return "";
    }
    let part = |hs: &[usize]| hs.iter().map(|h| clock_ms[*h]).sum::<i64>() as f64 / total as f64;
    let parts = [
        ("night", part(&[22, 23, 0, 1, 2, 3, 4])),
        ("morning", part(&[5, 6, 7, 8, 9, 10, 11])),
        ("afternoon", part(&[12, 13, 14, 15, 16])),
        ("evening", part(&[17, 18, 19, 20, 21])),
    ];
    let (k, v) = parts.iter().fold(("", 0.0), |acc, p| if p.1 > acc.1 { (p.0, p.1) } else { acc });
    if v >= 0.4 {
        k
    } else {
        "mixed"
    }
}

pub fn mood_line(mood: &str, range: &str) -> String {
    let base = match mood {
        "night" => "Mostly late nights",
        "morning" => "Mostly mornings",
        "afternoon" => "Mostly afternoons",
        "evening" => "Mostly evenings",
        "mixed" => "All through the day",
        _ => return String::new(),
    };
    let tail = match range {
        "week" => " this week",
        "month" => " this month",
        "year" => " this year",
        _ => " so far",
    };
    format!("{base}{tail}")
}

/// Goal streak over per-day minutes: (current, best). Today counts once it is met; until then the
/// streak runs up to yesterday.
pub fn goal_streaks(day_minutes: &HashMap<i64, u32>, today: i64, goal: u32) -> (u32, u32) {
    let goal = goal.max(1);
    let mut days: Vec<i64> = day_minutes.iter().filter(|(_, m)| **m >= goal).map(|(d, _)| *d).collect();
    days.sort_unstable();
    let mut best = 0u32;
    let mut run = 0u32;
    let mut prev: Option<i64> = None;
    for d in &days {
        run = if prev == Some(d - 1) { run + 1 } else { 1 };
        best = best.max(run);
        prev = Some(*d);
    }
    let met: HashSet<i64> = days.into_iter().collect();
    let mut d = if met.contains(&today) { today } else { today - 1 };
    let mut cur = 0;
    while met.contains(&d) {
        cur += 1;
        d -= 1;
    }
    (cur, best.max(cur))
}

/// Builds the whole Stats page. `rows` must be all plays (oldest first); `slots` are
/// (playlist id, title, position, track id); `album_ids` maps lowercased album name -> resolved id.
pub fn build_page(
    rows: &[PlayRow],
    slots: &[(String, String, u32, String)],
    range: &str,
    now: i64,
    tz_min: i32,
    goal: u32,
    album_ids: &HashMap<String, String>,
) -> StatsPage {
    let range = match range {
        "week" | "month" | "year" | "all" => range,
        _ => "week",
    };
    let today = (now + tz_min as i64 * 60).div_euclid(DAY);
    let first_day = rows.iter().filter(|r| r.ms > 0).map(|r| r.local_day()).min();
    let lay = layout(range, today, first_day);
    let key_pos: HashMap<i64, usize> = lay.keys.iter().enumerate().map(|(i, k)| (*k, i)).collect();
    let prev_pos: HashMap<i64, usize> = lay.prev_keys.iter().enumerate().map(|(i, k)| (*k, i)).collect();

    let mut accs: Vec<Acc> = (0..lay.keys.len()).map(|_| Acc::default()).collect();
    let mut prev_ms = vec![0i64; lay.prev_keys.len()];
    let mut clock = [0i64; 24];
    let (mut total_ms, mut prev_total_ms, mut plays) = (0i64, 0i64, 0u32);
    // per track: (latest row index, plays, ms)
    let mut tracks: HashMap<String, (usize, u32, i64)> = HashMap::new();
    // per main artist (lowercase): (display, id, latest thumb, plays, ms)
    let mut artists: HashMap<String, (String, String, String, u32, i64)> = HashMap::new();
    // per album (lowercase): (display, artist ms map, latest thumb, plays, ms)
    let mut albums: HashMap<String, (String, HashMap<String, i64>, String, u32, i64)> = HashMap::new();
    let mut artist_first: HashMap<String, i64> = HashMap::new();
    let mut day_ms: HashMap<i64, i64> = HashMap::new();
    let mut active: HashSet<i64> = HashSet::new();
    // skips over the last 90 days: (latest row, skips, rows)
    let mut skips: HashMap<String, (usize, u32, u32)> = HashMap::new();
    let groups = song_groups(rows);

    for (i, r) in rows.iter().enumerate() {
        let day = r.local_day();
        let a = main_artist(&r.artist);
        let ak = a.to_lowercase();
        if !ak.is_empty() {
            let f = artist_first.entry(ak.clone()).or_insert(day);
            *f = (*f).min(day);
        }
        if r.ms > 0 {
            *day_ms.entry(day).or_default() += r.ms;
            active.insert(day);
        }
        if day >= today - 89 {
            let s = skips.entry(r.track_id.clone()).or_insert((i, 0, 0));
            s.0 = i;
            s.2 += 1;
            if r.skipped {
                s.1 += 1;
            }
        }
        let key = key_of(lay.unit, day);
        if day >= lay.start_day && day <= today {
            if let Some(&p) = key_pos.get(&key) {
                let acc = &mut accs[p];
                acc.ms += r.ms;
                if r.counts() {
                    acc.plays += 1;
                    plays += 1;
                }
                let sk = groups[i].clone();
                *acc.tracks.entry(sk.clone()).or_default() += r.ms;
                acc.titles.entry(sk.clone()).or_insert_with(|| r.title.clone());
                acc.ids.entry(sk.clone()).or_insert_with(|| r.track_id.clone());
                acc.hours[r.local_hour()] += r.ms;
                total_ms += r.ms;
                clock[r.local_hour()] += r.ms;
                let t = tracks.entry(sk).or_insert((i, 0, 0));
                t.0 = i;
                if r.counts() {
                    t.1 += 1;
                }
                t.2 += r.ms;
                if !ak.is_empty() {
                    let e = artists.entry(ak.clone()).or_insert((a.clone(), String::new(), String::new(), 0, 0));
                    if r.counts() {
                        e.3 += 1;
                    }
                    e.4 += r.ms;
                    e.2 = r.thumb.clone();
                    if e.1.is_empty() {
                        if let Some(id) = &r.artist_id {
                            e.1 = id.clone();
                        }
                    }
                }
                if let Some(al) = r.album.as_deref().map(str::trim).filter(|x| !x.is_empty()) {
                    let e = albums.entry(al.to_lowercase()).or_insert((al.to_string(), HashMap::new(), String::new(), 0, 0));
                    *e.1.entry(a.clone()).or_default() += r.ms;
                    e.2 = r.thumb.clone();
                    if r.counts() {
                        e.3 += 1;
                    }
                    e.4 += r.ms;
                }
            }
        } else if day >= lay.prev_start_day && day < lay.start_day {
            if let Some(&p) = prev_pos.get(&key) {
                prev_ms[p] += r.ms;
                prev_total_ms += r.ms;
            }
        }
    }

    let first_play_at = rows.iter().filter(|r| r.ms > 0).map(|r| r.at).min().unwrap_or(0);
    let tz_s = tz_min as i64 * 60;
    let bars: Vec<StatsBar> = lay
        .keys
        .iter()
        .zip(accs.iter())
        .map(|(k, acc)| {
            let (label, title, d0, d1) = bar_labels(lay.unit, *k);
            let label = if range == "month" { format!("{}", civil(*k).2) } else { label };
            let ph = peak(&acc.hours);
            let top = top_of(&acc.tracks);
            let line = match (top, ph) {
                (Some((id, _)), h) if acc.ms > 0 => {
                    let name = acc.titles.get(id).cloned().unwrap_or_default();
                    if h >= 0 {
                        format!("Mostly {name}, around {}", hour_label(h as u32))
                    } else {
                        format!("Mostly {name}")
                    }
                }
                _ => String::new(),
            };
            StatsBar {
                label,
                title,
                minutes: (acc.ms / 60_000) as u32,
                plays: acc.plays,
                line,
                top_track_id: top.and_then(|t| acc.ids.get(t.0).cloned()).unwrap_or_default(),
                peak_hour: if acc.ms > 0 { ph } else { -1 },
                start: d0 * DAY - tz_s,
                end: d1 * DAY - tz_s,
                is_current: today >= d0 && today < d1,
            }
        })
        .collect();
    // average over bars from the first bar that has history
    let first_idx = first_day.map(|f| key_of(lay.unit, f)).and_then(|fk| lay.keys.iter().position(|k| *k >= fk)).unwrap_or(0);
    let counted = &bars[first_idx.min(bars.len())..];
    let avg = if counted.is_empty() || first_day.is_none() {
        0
    } else {
        counted.iter().map(|b| b.minutes as u64).sum::<u64>() / counted.len() as u64
    } as u32;

    // top tracks
    let mut tv: Vec<(usize, u32, i64)> = tracks.into_values().collect();
    tv.sort_by(|a, b| b.1.cmp(&a.1).then(b.2.cmp(&a.2)).then(b.0.cmp(&a.0)));
    let top_tracks: Vec<TrackStat> = tv
        .iter()
        .filter(|t| t.1 > 0)
        .take(25)
        .map(|(i, n, ms)| TrackStat { track: rows[*i].track(), plays: *n, minutes: (*ms / 60_000) as u32 })
        .collect();

    let mut av: Vec<(String, String, String, u32, i64)> = artists.into_values().collect();
    av.sort_by(|a, b| b.4.cmp(&a.4).then(b.3.cmp(&a.3)).then(a.0.cmp(&b.0)));
    let named = |x: &(String, String, String, u32, i64)| NamedCount {
        id: x.1.clone(),
        name: x.0.clone(),
        thumb: x.2.clone(),
        plays: x.3,
        minutes: (x.4 / 60_000) as u32,
    };
    let top_artists: Vec<NamedCount> = av.iter().filter(|a| a.3 > 0).take(10).map(named).collect();

    // new to you: first-ever play inside this range (all: last 30 days)
    let new_from = if range == "all" { today - 29 } else { lay.start_day };
    let new_artists: Vec<NamedCount> = av
        .iter()
        .filter(|a| a.3 > 0 && artist_first.get(&a.0.to_lowercase()).map(|d| *d >= new_from).unwrap_or(false))
        .take(12)
        .map(named)
        .collect();

    let mut alv: Vec<(String, (String, HashMap<String, i64>, String, u32, i64))> = albums.into_iter().collect();
    alv.sort_by(|a, b| b.1 .4.cmp(&a.1 .4).then(b.1 .3.cmp(&a.1 .3)).then(a.0.cmp(&b.0)));
    let top_albums: Vec<AlbumStat> = alv
        .into_iter()
        .filter(|a| a.1 .3 > 0)
        .take(10)
        .map(|(k, (name, arts, thumb, n, ms))| AlbumStat {
            name,
            artist: top_of(&arts).map(|x| x.0.clone()).unwrap_or_default(),
            album_id: album_ids.get(&k).cloned().unwrap_or_default(),
            thumb,
            plays: n,
            minutes: (ms / 60_000) as u32,
        })
        .collect();

    // skipped often (last 90 days)
    let mut slot_map: HashMap<&str, Vec<PlaylistSlot>> = HashMap::new();
    for (pid, title, pos, tid) in slots {
        slot_map.entry(tid.as_str()).or_default().push(PlaylistSlot { playlist_id: pid.clone(), title: title.clone(), position: *pos });
    }
    let mut sv: Vec<(&String, &(usize, u32, u32))> = skips.iter().filter(|(_, s)| s.1 >= 2 && s.1 * 2 >= s.2).collect();
    sv.sort_by(|a, b| b.1 .1.cmp(&a.1 .1).then((b.1 .1 * 100 / b.1 .2).cmp(&(a.1 .1 * 100 / a.1 .2))).then(a.0.cmp(b.0)));
    let skipped: Vec<SkippedTrack> = sv
        .into_iter()
        .take(5)
        .map(|(tid, s)| SkippedTrack {
            track: rows[s.0].track(),
            skips: s.1,
            plays: s.2,
            playlists: slot_map.get(tid.as_str()).cloned().unwrap_or_default(),
        })
        .collect();

    // goal
    let goal = goal.clamp(5, 240);
    let day_min: HashMap<i64, u32> = day_ms.iter().map(|(d, ms)| (*d, (*ms / 60_000) as u32)).collect();
    let (goal_streak, goal_best) = goal_streaks(&day_min, today, goal);
    let goal_week = (0..7)
        .map(|i| {
            let d = today - 6 + i;
            GoalDay { label: WD_LETTER[weekday(d)].to_string(), minutes: day_min.get(&d).copied().unwrap_or(0), is_today: d == today }
        })
        .collect();

    let ph = peak(&clock);
    let mood = mood_of(&clock);
    let period_label = match lay.unit {
        Unit::Day => span_label(lay.start_day, today),
        _ if range == "year" => {
            let (a, b) = (lay.keys[0], lay.keys[lay.keys.len() - 1]);
            format!("{} {} – {} {}", MON_SHORT[a.rem_euclid(12) as usize], a.div_euclid(12), MON_SHORT[b.rem_euclid(12) as usize], b.div_euclid(12))
        }
        _ => match first_day {
            Some(f) => {
                let (y, m, d) = civil(f);
                format!("Since {d} {} {y}", MON_SHORT[m as usize - 1])
            }
            None => String::new(),
        },
    };
    let history_days = first_day.map(|f| (today - f + 1).max(1) as u32).unwrap_or(0);

    StatsPage {
        range: range.to_string(),
        period_label,
        minutes: (total_ms / 60_000) as u32,
        prev_minutes: (prev_total_ms / 60_000) as u32,
        has_prev: range != "all" && first_day.map(|f| f < lay.start_day).unwrap_or(false) && prev_total_ms >= 60_000,
        plays,
        bars,
        prev_bars: prev_ms.into_iter().map(|x| (x / 60_000) as u32).collect(),
        avg_minutes: avg,
        clock: clock.iter().map(|x| (*x / 60_000) as u32).collect(),
        peak_hour: if total_ms > 0 { ph } else { -1 },
        mood: mood.to_string(),
        mood_line: mood_line(mood, range),
        top_tracks,
        top_artists,
        top_albums,
        new_artists,
        new_artists_ready: history_days >= NEW_ARTISTS_MIN_DAYS,
        skipped,
        goal_minutes: goal,
        goal_week,
        goal_streak,
        goal_best,
        today_minutes: day_min.get(&today).copied().unwrap_or(0),
        active_days: active.len() as u32,
        history_days,
        first_play_at,
    }
}

/// Window [from, to) in epoch seconds of the range as the page lays it out (to = end of today).
pub fn range_window(range: &str, now: i64, tz_min: i32, first_day: Option<i64>) -> (i64, i64) {
    let today = (now + tz_min as i64 * 60).div_euclid(DAY);
    let lay = layout(range, today, first_day);
    let tz_s = tz_min as i64 * 60;
    (lay.start_day * DAY - tz_s, (today + 1) * DAY - tz_s)
}

/// Tracks played around `hour` (local) inside the range, by listening time.
pub fn hour_detail(rows: &[PlayRow], range: &str, now: i64, tz_min: i32, hour: u32) -> HourDetail {
    let first_day = rows.iter().filter(|r| r.ms > 0).map(|r| r.local_day()).min();
    let (from, to) = range_window(range, now, tz_min, first_day);
    let h = (hour % 24) as usize;
    let mut m: HashMap<String, (usize, u32, i64)> = HashMap::new();
    let mut total = 0i64;
    let groups = song_groups(rows);
    for (i, r) in rows.iter().enumerate() {
        if r.at < from || r.at >= to || r.local_hour() != h {
            continue;
        }
        total += r.ms;
        let e = m.entry(groups[i].clone()).or_insert((i, 0, 0));
        e.0 = i;
        if r.counts() {
            e.1 += 1;
        }
        e.2 += r.ms;
    }
    let mut v: Vec<(usize, u32, i64)> = m.into_values().collect();
    v.sort_by(|a, b| b.2.cmp(&a.2).then(b.1.cmp(&a.1)));
    HourDetail {
        hour: h as u32,
        title: format!("Around {}", hour_label(h as u32)),
        minutes: (total / 60_000) as u32,
        tracks: v
            .into_iter()
            .take(20)
            .map(|(i, n, ms)| TrackStat { track: rows[i].track(), plays: n, minutes: (ms / 60_000) as u32 })
            .collect(),
    }
}

/// Plays inside [from, to), newest first.
pub fn plays_between(rows: &[PlayRow], from: i64, to: i64, limit: usize) -> Vec<PlayEntry> {
    rows.iter()
        .rev()
        .filter(|r| r.at >= from && r.at < to && r.ms > 0)
        .take(limit)
        .map(|r| {
            let local = (r.at + r.tz as i64 * 60).rem_euclid(DAY);
            let (h, mi) = ((local / 3600) as u32, ((local % 3600) / 60) as u32);
            let (n, ap) = match h {
                0 => (12, "AM"),
                1..=11 => (h, "AM"),
                12 => (12, "PM"),
                _ => (h - 12, "PM"),
            };
            PlayEntry {
                track: r.track(),
                started_at: r.at,
                time: format!("{n}:{mi:02} {ap}"),
                listened_s: (r.ms / 1000) as u32,
                skipped: r.skipped,
            }
        })
        .collect()
}

/// A play-through mix of the top artists in the range: each artist's most played tracks, interleaved.
pub fn artist_mix(rows: &[PlayRow], page: &StatsPage, now: i64, tz_min: i32, only: Option<&str>, limit: usize) -> Vec<Track> {
    let first_day = rows.iter().filter(|r| r.ms > 0).map(|r| r.local_day()).min();
    let (from, to) = range_window(&page.range, now, tz_min, first_day);
    let wanted: Vec<String> = match only {
        Some(a) => vec![main_artist(a).to_lowercase()],
        None => page.top_artists.iter().take(5).map(|a| a.name.to_lowercase()).collect(),
    };
    let mut per: Vec<Vec<(usize, u32, i64)>> = vec![vec![]; wanted.len()];
    let mut idx: HashMap<(usize, String), usize> = HashMap::new();
    for (i, r) in rows.iter().enumerate() {
        if r.at < from || r.at >= to {
            continue;
        }
        let a = main_artist(&r.artist).to_lowercase();
        let Some(w) = wanted.iter().position(|x| *x == a) else { continue };
        let k = (w, r.track_id.clone());
        let p = *idx.entry(k).or_insert_with(|| {
            per[w].push((i, 0, 0));
            per[w].len() - 1
        });
        let e = &mut per[w][p];
        e.0 = i;
        if r.counts() {
            e.1 += 1;
        }
        e.2 += r.ms;
    }
    for v in per.iter_mut() {
        v.sort_by(|a, b| b.1.cmp(&a.1).then(b.2.cmp(&a.2)));
    }
    let mut out = vec![];
    let mut seen = HashSet::new();
    let longest = per.iter().map(|v| v.len()).max().unwrap_or(0);
    'o: for k in 0..longest {
        for v in &per {
            if let Some((i, _, _)) = v.get(k) {
                let t = rows[*i].track();
                if seen.insert(t.id.clone()) {
                    out.push(t);
                    if out.len() >= limit {
                        break 'o;
                    }
                }
            }
        }
    }
    out
}

pub fn recap(page: &StatsPage) -> Recap {
    let change = if page.has_prev && page.prev_minutes > 0 {
        Some(((page.minutes as f64 - page.prev_minutes as f64) / page.prev_minutes as f64 * 100.0).round() as i32)
    } else {
        None
    };
    Recap {
        range: page.range.clone(),
        title: match page.range.as_str() {
            "week" => "Your week",
            "month" => "Your month",
            "year" => "Your year",
            _ => "All time",
        }
        .to_string(),
        period_label: page.period_label.clone(),
        minutes: page.minutes,
        plays: page.plays,
        change_percent: change,
        top_artist: page.top_artists.first().cloned(),
        top_track: page.top_tracks.first().cloned(),
        top_tracks: page.top_tracks.iter().take(5).cloned().collect(),
        top_artists: page.top_artists.iter().take(3).cloned().collect(),
        mood_line: page.mood_line.clone(),
        peak_hour: page.peak_hour,
        goal_streak: page.goal_streak,
        new_artists: if page.new_artists_ready { page.new_artists.len() as u32 } else { 0 },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // 2026-10-20 (Tuesday) 22:00 IST
    const TZ: i32 = 330;
    fn now() -> i64 {
        days_from_civil(2026, 10, 20) * DAY + 22 * 3600 - TZ as i64 * 60
    }
    fn today() -> i64 {
        days_from_civil(2026, 10, 20)
    }
    fn play(day: i64, hour: i64, min: i64, id: &str, title: &str, artist: &str, album: &str, skipped: bool) -> PlayRow {
        PlayRow {
            track_id: id.into(),
            title: title.into(),
            artist: artist.into(),
            artist_id: Some(format!("UC{}", artist.len())),
            album: if album.is_empty() { None } else { Some(album.into()) },
            thumb: format!("https://img/{id}"),
            duration_s: 200,
            at: day * DAY + hour * 3600 - TZ as i64 * 60,
            ms: min * 60_000,
            skipped,
            tz: TZ,
        }
    }
    fn sample() -> Vec<PlayRow> {
        let t = today();
        let mut v = vec![
            // previous week: 60 min
            play(t - 10, 21, 30, "a", "Darshana", "Hesham Abdul Wahab, Darshana", "Hridayam", false),
            play(t - 9, 21, 30, "b", "Illuminati", "Sushin Shyam & Dabzee", "Aavesham", false),
            // this week
            play(t - 3, 23, 40, "b", "Illuminati", "Sushin Shyam & Dabzee", "Aavesham", false),
            play(t - 3, 23, 20, "a", "Darshana", "Hesham Abdul Wahab", "Hridayam", false),
            play(t - 1, 22, 15, "b", "Illuminati", "Sushin Shyam", "Aavesham", false),
            play(t, 21, 10, "c", "Kuthanthram", "Sushin Shyam, Vedan", "Manjummel Boys", false),
            play(t, 14, 0, "d", "Skipper", "Nobody", "", true),
        ];
        v.sort_by_key(|r| r.at);
        v
    }

    #[test]
    fn same_song_from_two_ids_is_one_row() {
        let t = today();
        let mut v = vec![
            play(t - 2, 15, 4, "y1", "Young And Beautiful", "Lana Del Rey", "", false),
            play(t - 2, 16, 4, "y1", "Young And Beautiful", "Lana Del Rey", "", false),
            play(t - 1, 15, 4, "y2", "Young and Beautiful ", "Lana Del Rey", "", false),
            play(t - 1, 16, 4, "y2", "  young and beautiful", "Lana Del Rey", "", false),
            // same title, other artist: stays separate
            play(t - 1, 17, 4, "y3", "Young and Beautiful", "Someone Else", "", false),
            play(t, 15, 4, "h", "Hips Don't Lie", "Shakira, Ed Sheeran", "", false),
            play(t, 16, 4, "h", "Hips Don't Lie", "Shakira, Ed Sheeran", "", false),
            play(t, 17, 4, "h", "Hips Don't Lie", "Shakira, Ed Sheeran", "", false),
        ];
        v.sort_by_key(|r| r.at);
        let p = build_page(&v, &[], "week", now(), TZ, 30, &HashMap::new());
        let yb: Vec<_> = p.top_tracks.iter().filter(|x| x.track.artists == "Lana Del Rey").collect();
        assert_eq!(yb.len(), 1);
        assert_eq!(yb[0].plays, 4);
        assert_eq!(p.top_tracks[0].plays, 4);
        assert_eq!(p.top_tracks.len(), 3);
        assert_eq!(recap(&p).top_tracks.len(), 3);
        assert_eq!(song_key("a", "Young And Beautiful", "Lana Del Rey"), song_key("b", " young and  beautiful ", "Lana Del Rey & X"));
        assert_ne!(song_key("a", "Young And Beautiful", "Lana Del Rey"), song_key("b", "Young And Beautiful", "Other"));
        let h = hour_detail(&v, "week", now(), TZ, 15);
        assert_eq!(h.tracks.iter().filter(|x| x.track.artists == "Lana Del Rey").count(), 1);
        // db top list merge
        let tr = |id: &str, title: &str| Track { id: id.into(), title: title.into(), artists: "Lana Del Rey".into(), artist_id: None, album: None, album_id: None, thumb: String::new(), duration_s: 0 };
        let m = crate::db::merge_same_songs(vec![(tr("x", "Other"), 3, 9), (tr("y1", "Young And Beautiful"), 2, 8), (tr("y2", "Young and Beautiful"), 2, 8)], 10);
        assert_eq!(m.len(), 2);
        assert_eq!(m[0].0.id, "y1");
        assert_eq!(m[0].1, 4);
    }

    #[test]
    fn calendar_roundtrip() {
        for z in [-1000, 0, 365, 20_000, 20_746] {
            let (y, m, d) = civil(z);
            assert_eq!(days_from_civil(y, m, d), z);
        }
        assert_eq!(civil(today()), (2026, 10, 20));
        assert_eq!(WD_NAME[weekday(today())], "Tuesday");
        assert_eq!(hour_label(0), "12 AM");
        assert_eq!(hour_label(23), "11 PM");
        assert_eq!(hour_label(12), "12 PM");
    }

    #[test]
    fn week_summary_bars_and_change() {
        let p = build_page(&sample(), &[], "week", now(), TZ, 30, &HashMap::new());
        assert_eq!(p.bars.len(), 7);
        assert_eq!(p.prev_bars.len(), 7);
        assert_eq!(p.minutes, 85);
        assert_eq!(p.prev_minutes, 60);
        assert!(p.has_prev);
        assert_eq!(p.bars[6].label, "T");
        assert!(p.bars[6].is_current);
        assert_eq!(p.bars[6].minutes, 10);
        assert_eq!(p.bars[3].minutes, 60);
        assert_eq!(p.bars[3].title, "Saturday 17 Oct");
        assert_eq!(p.bars[3].line, "Mostly Illuminati, around 11 PM");
        assert_eq!(p.bars[3].peak_hour, 23);
        assert_eq!(p.bars[4].minutes, 0);
        assert_eq!(p.bars[4].line, "");
        // ghost bars: t-10 sits at index 3 of the previous week, t-9 at 4
        assert_eq!(p.prev_bars[3], 30);
        assert_eq!(p.prev_bars[4], 30);
        assert_eq!(p.period_label, "14 – 20 Oct");
        assert_eq!(p.plays, 4); // the 0-minute skip does not count
        // bars start/end are local midnights
        assert_eq!(p.bars[6].end - p.bars[6].start, DAY);
        let r = recap(&p);
        assert_eq!(r.change_percent, Some(42));
        assert_eq!(r.title, "Your week");
    }

    #[test]
    fn clock_peak_and_mood() {
        let p = build_page(&sample(), &[], "week", now(), TZ, 30, &HashMap::new());
        assert_eq!(p.clock.len(), 24);
        assert_eq!(p.clock[23], 60);
        assert_eq!(p.clock[22], 15);
        assert_eq!(p.peak_hour, 23);
        assert_eq!(p.mood, "night");
        assert_eq!(p.mood_line, "Mostly late nights this week");
        let mut c = [0i64; 24];
        c[9] = 60_000 * 10;
        c[14] = 60_000 * 10;
        c[19] = 60_000 * 10;
        c[23] = 60_000 * 10;
        assert_eq!(mood_of(&c), "mixed");
        assert_eq!(mood_of(&[0; 24]), "");
    }

    #[test]
    fn tops_group_by_main_artist_and_album_name() {
        let mut ids = HashMap::new();
        ids.insert("aavesham".to_string(), "MPREb_x".to_string());
        let p = build_page(&sample(), &[], "week", now(), TZ, 30, &ids);
        assert_eq!(p.top_artists[0].name, "Sushin Shyam");
        assert_eq!(p.top_artists[0].minutes, 65);
        assert_eq!(p.top_artists[0].plays, 3);
        assert_eq!(p.top_tracks[0].track.id, "b");
        assert_eq!(p.top_tracks[0].plays, 2);
        assert!(p.top_tracks.iter().all(|t| t.track.id != "d"));
        assert_eq!(p.top_albums[0].name, "Aavesham");
        assert_eq!(p.top_albums[0].album_id, "MPREb_x");
        assert_eq!(p.top_albums[0].artist, "Sushin Shyam");
        assert_eq!(p.top_albums[1].name, "Hridayam");
        assert_eq!(p.top_albums[1].album_id, "");
    }

    #[test]
    fn new_artists_hidden_until_two_weeks() {
        let p = build_page(&sample(), &[], "week", now(), TZ, 30, &HashMap::new());
        assert_eq!(p.history_days, 11);
        assert!(!p.new_artists_ready);
        // Sushin and Hesham were first played 9-10 days ago (before this week); Vedan's song counts as Sushin
        assert!(p.new_artists.is_empty(), "{:?}", p.new_artists);
        let mut rows = sample();
        rows.insert(0, play(today() - 30, 10, 5, "z", "Old", "Old Artist", "", false));
        rows.push(play(today(), 8, 5, "n", "Fresh", "New Person", "", false));
        rows.sort_by_key(|r| r.at);
        let p = build_page(&rows, &[], "week", now(), TZ, 30, &HashMap::new());
        assert!(p.new_artists_ready);
        assert_eq!(p.new_artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>(), vec!["New Person"]);
    }

    #[test]
    fn skipped_with_playlists() {
        let t = today();
        let mut rows = sample();
        for d in 1..4 {
            rows.push(play(t - d, 10, 0, "s", "Skip me", "X", "", true));
        }
        rows.push(play(t - 5, 10, 3, "s", "Skip me", "X", "", false));
        rows.sort_by_key(|r| r.at);
        let slots = vec![("pl1".to_string(), "Night drive".to_string(), 4u32, "s".to_string())];
        let p = build_page(&rows, &slots, "week", now(), TZ, 30, &HashMap::new());
        assert_eq!(p.skipped.len(), 1);
        let s = &p.skipped[0];
        assert_eq!((s.skips, s.plays), (3, 4));
        assert_eq!(s.playlists[0].title, "Night drive");
        assert_eq!(s.playlists[0].position, 4);
        // one skip is not "often"
        let p = build_page(&sample(), &[], "week", now(), TZ, 30, &HashMap::new());
        assert!(p.skipped.is_empty());
    }

    #[test]
    fn goal_week_and_streak() {
        let p = build_page(&sample(), &[], "week", now(), TZ, 15, &HashMap::new());
        assert_eq!(p.goal_minutes, 15);
        assert_eq!(p.goal_week.len(), 7);
        assert!(p.goal_week[6].is_today);
        assert_eq!(p.today_minutes, 10);
        // today (10) misses 15; yesterday (15) meets it; the day before had nothing
        assert_eq!(p.goal_streak, 1);
        let mut m = HashMap::new();
        for d in [1, 2, 3, 5, 6, 7, 8, 10] {
            m.insert(d, 40);
        }
        assert_eq!(goal_streaks(&m, 10, 30), (1, 4));
        assert_eq!(goal_streaks(&m, 9, 30), (4, 4));
        m.insert(9, 10);
        assert_eq!(goal_streaks(&m, 9, 30), (4, 4));
        assert_eq!(goal_streaks(&HashMap::new(), 9, 30), (0, 0));
    }

    #[test]
    fn month_year_all_layouts() {
        let rows = sample();
        let m = build_page(&rows, &[], "month", now(), TZ, 30, &HashMap::new());
        assert_eq!(m.bars.len(), 30);
        assert_eq!(m.bars[29].label, "20");
        assert_eq!(m.minutes, 145);
        assert!(!m.has_prev); // history starts inside the month
        let y = build_page(&rows, &[], "year", now(), TZ, 30, &HashMap::new());
        assert_eq!(y.bars.len(), 12);
        assert_eq!(y.bars[11].label, "Oct");
        assert_eq!(y.bars[11].title, "October 2026");
        assert_eq!(y.bars[11].minutes, 145);
        assert_eq!(y.period_label, "Nov 2025 – Oct 2026");
        let a = build_page(&rows, &[], "all", now(), TZ, 30, &HashMap::new());
        assert_eq!(a.bars.len(), 1);
        assert!(a.prev_bars.is_empty());
        assert!(!a.has_prev);
        assert_eq!(a.period_label, "Since 10 Oct 2026");
        assert_eq!(a.mood_line, "Mostly late nights so far");
    }

    #[test]
    fn sparse_and_empty() {
        let p = build_page(&[], &[], "week", now(), TZ, 30, &HashMap::new());
        assert_eq!(p.bars.len(), 7);
        assert_eq!(p.minutes, 0);
        assert_eq!(p.avg_minutes, 0);
        assert_eq!(p.peak_hour, -1);
        assert_eq!(p.mood_line, "");
        assert_eq!(p.history_days, 0);
        let rows = vec![play(today() - 1, 20, 30, "a", "A", "B", "", false), play(today(), 20, 20, "a", "A", "B", "", false)];
        let p = build_page(&rows, &[], "week", now(), TZ, 30, &HashMap::new());
        assert_eq!(p.history_days, 2);
        assert_eq!(p.active_days, 2);
        // the average only counts days since the first play
        assert_eq!(p.avg_minutes, 25);
        let a = build_page(&rows, &[], "all", now(), TZ, 30, &HashMap::new());
        assert_eq!(a.bars.len(), 1);
    }

    #[test]
    fn hour_and_day_plays_and_mix() {
        let rows = sample();
        let h = hour_detail(&rows, "week", now(), TZ, 23);
        assert_eq!(h.title, "Around 11 PM");
        assert_eq!(h.minutes, 60);
        assert_eq!(h.tracks[0].track.id, "b");
        assert_eq!(h.tracks.len(), 2);
        let p = build_page(&rows, &[], "week", now(), TZ, 30, &HashMap::new());
        let b = &p.bars[3];
        let d = plays_between(&rows, b.start, b.end, 50);
        assert_eq!(d.len(), 2);
        assert_eq!(d[0].time, "11:00 PM");
        let mix = artist_mix(&rows, &p, now(), TZ, None, 50);
        assert_eq!(mix[0].id, "b");
        assert!(mix.iter().any(|t| t.id == "a"));
        let only = artist_mix(&rows, &p, now(), TZ, Some("Sushin Shyam"), 50);
        assert_eq!(only.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), vec!["b", "c"]);
    }

    #[test]
    fn db_rows_slots_and_playlist_undo() {
        let d = tempfile::tempdir().unwrap();
        let db = crate::db::Db::open(&format!("{}/t.db", d.path().display())).unwrap();
        let t = |id: &str| Track {
            id: id.into(),
            title: id.to_uppercase(),
            artists: "A".into(),
            artist_id: None,
            album: Some("Al".into()),
            album_id: None,
            duration_s: 100,
            thumb: String::new(),
        };
        db.create_playlist("p1", "Night drive", "", "", "local", &[t("x"), t("s"), t("y")]).unwrap();
        db.record_play(&t("s"), 1000, 2_000, true, 330, "device").unwrap();
        db.record_play(&t("x"), 2000, 90_000, false, 330, "device").unwrap();
        let rows = db.play_rows(0).unwrap();
        assert_eq!(rows.len(), 2);
        assert!(rows[0].skipped && rows[0].tz == 330 && rows[1].ms == 90_000);
        let slots = db.playlist_slots().unwrap();
        assert!(slots.contains(&("p1".to_string(), "Night drive".to_string(), 1, "s".to_string())));
        db.remove_from_playlist("p1", 1).unwrap();
        assert_eq!(db.playlist_tracks("p1").unwrap().iter().map(|x| x.id.clone()).collect::<Vec<_>>(), vec!["x", "y"]);
        db.insert_into_playlist("p1", 1, &t("s")).unwrap();
        assert_eq!(db.playlist_tracks("p1").unwrap().iter().map(|x| x.id.clone()).collect::<Vec<_>>(), vec!["x", "s", "y"]);
        db.kv_set("stats_album:al", "MPRE1").unwrap();
        db.kv_set("other", "z").unwrap();
        assert_eq!(db.kv_prefix("stats_album:"), vec![("al".to_string(), "MPRE1".to_string())]);
    }
}
