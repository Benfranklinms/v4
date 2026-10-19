//! Listening stats computed from the plays table.

use crate::db::Db;
use crate::isa::genre_of;
use crate::model::*;
use crate::CoreError;
use std::collections::HashMap;

const DAY: i64 = 86_400;

pub fn range_start(range: &str, now: i64, tz_min: i32) -> (i64, i64, usize) {
    // returns (start, previous_start, buckets)
    let local_day = (now + tz_min as i64 * 60).div_euclid(DAY);
    let today0 = local_day * DAY - tz_min as i64 * 60;
    match range {
        "week" => (today0 - 6 * DAY, today0 - 13 * DAY, 7),
        "month" => (today0 - 29 * DAY, today0 - 59 * DAY, 30),
        "year" => (today0 - 364 * DAY, today0 - 729 * DAY, 12),
        _ => (0, 0, 12),
    }
}

pub fn summary(db: &Db, range: &str, now: i64, tz_min: i32) -> Result<StatsSummary, CoreError> {
    let (start, prev_start, buckets) = range_start(range, now, tz_min);
    let rows = db.plays_since(prev_start)?;
    let mut minutes_ms: i64 = 0;
    let mut prev_ms: i64 = 0;
    let mut plays = 0u32;
    let mut clock = vec![0i64; 24];
    let mut series = vec![0i64; buckets];
    let mut artists: HashMap<String, (String, String, u32, i64)> = HashMap::new();
    let mut tracks: HashMap<String, (String, String, u32, i64)> = HashMap::new();
    let mut genres: HashMap<String, i64> = HashMap::new();
    let first = rows.iter().filter(|r| r.0 >= start).map(|r| r.0).min().unwrap_or(now);
    for (at, ms, tz, artist, artist_id, thumb, title, tid, album) in rows.iter() {
        if *at < start {
            prev_ms += ms;
            continue;
        }
        minutes_ms += ms;
        if *ms >= 30_000 {
            plays += 1;
        }
        let local = at + *tz as i64 * 60;
        let hour = (local.rem_euclid(DAY) / 3600) as usize;
        clock[hour] += ms;
        let idx = match range {
            "week" | "month" => ((at - start) / DAY) as usize,
            "year" => (((at - start) / DAY) as usize * 12 / 365).min(11),
            _ => {
                let span = (now - first).max(1);
                (((at - first) as f64 / span as f64) * 11.999) as usize
            }
        };
        if idx < series.len() {
            series[idx] += ms;
        }
        let main = artist.split(&[',', '&'][..]).next().unwrap_or("").trim().to_string();
        let ae = artists
            .entry(main.to_lowercase())
            .or_insert((artist_id.clone().unwrap_or_default(), main.clone(), 0, 0));
        ae.2 += 1;
        ae.3 += ms;
        if ae.0.is_empty() {
            if let Some(a) = artist_id {
                ae.0 = a.clone();
            }
        }
        let te = tracks.entry(tid.clone()).or_insert((title.clone(), thumb.clone(), 0, 0));
        te.2 += 1;
        te.3 += ms;
        *genres.entry(genre_of(title, artist, album.as_deref().unwrap_or(""))).or_default() += ms;
    }
    let mut top_artists: Vec<NamedCount> = artists
        .into_values()
        .map(|(id, name, n, ms)| NamedCount { id, name, thumb: String::new(), plays: n, minutes: (ms / 60_000) as u32 })
        .collect();
    top_artists.sort_by(|a, b| b.minutes.cmp(&a.minutes).then(b.plays.cmp(&a.plays)));
    top_artists.truncate(10);
    // artist thumb: use the most played track's art by that artist (platforms can fetch real avatars lazily)
    for a in top_artists.iter_mut() {
        if let Some(r) = rows.iter().rev().find(|r| r.3.to_lowercase().starts_with(&a.name.to_lowercase())) {
            a.thumb = r.5.clone();
        }
    }
    let mut top_tracks: Vec<NamedCount> = tracks
        .into_iter()
        .map(|(id, (name, thumb, n, ms))| NamedCount { id, name, thumb, plays: n, minutes: (ms / 60_000) as u32 })
        .collect();
    top_tracks.sort_by(|a, b| b.plays.cmp(&a.plays).then(b.minutes.cmp(&a.minutes)));
    top_tracks.truncate(10);
    let total_g: i64 = genres.values().sum::<i64>().max(1);
    let mut gv: Vec<GenreShare> = genres
        .into_iter()
        .map(|(name, ms)| GenreShare { name, percent: ((ms * 100) as f64 / total_g as f64).round() as u32 })
        .filter(|g| g.percent > 0)
        .collect();
    gv.sort_by(|a, b| b.percent.cmp(&a.percent));
    gv.truncate(6);
    let (streak, best, week) = streaks(db, now, tz_min)?;
    Ok(StatsSummary {
        range: range.to_string(),
        minutes: (minutes_ms / 60_000) as u32,
        plays,
        prev_minutes: (prev_ms / 60_000) as u32,
        streak_days: streak,
        best_streak_days: best,
        top_artists,
        top_tracks,
        clock: clock.into_iter().map(|x| (x / 60_000) as u32).collect(),
        series: series.into_iter().map(|x| (x / 60_000) as u32).collect(),
        genres: gv,
        streak_week: week,
    })
}

pub fn streaks(db: &Db, now: i64, tz_min: i32) -> Result<(u32, u32, Vec<bool>), CoreError> {
    let days = db.play_days()?;
    let today = (now + tz_min as i64 * 60).div_euclid(DAY);
    Ok(streaks_from_days(&days, today))
}

pub fn streaks_from_days(days: &[i64], today: i64) -> (u32, u32, Vec<bool>) {
    let mut best = 0u32;
    let mut run = 0u32;
    let mut prev: Option<i64> = None;
    for d in days {
        run = match prev {
            Some(p) if *d == p + 1 => run + 1,
            Some(p) if *d == p => run,
            _ => 1,
        };
        best = best.max(run);
        prev = Some(*d);
    }
    // current streak: ends today or yesterday
    let set: std::collections::HashSet<i64> = days.iter().copied().collect();
    let mut cur = 0u32;
    let mut d = if set.contains(&today) { today } else { today - 1 };
    while set.contains(&d) {
        cur += 1;
        d -= 1;
    }
    let week = (0..7).map(|i| set.contains(&(today - 6 + i))).collect();
    (cur, best.max(cur), week)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn streak_math() {
        let (c, b, w) = streaks_from_days(&[1, 2, 3, 7, 8, 9, 10, 11, 20, 21], 21);
        assert_eq!(c, 2);
        assert_eq!(b, 5);
        assert_eq!(w.len(), 7);
        assert!(w[6] && w[5] && !w[4]);
        let (c, _, _) = streaks_from_days(&[19, 20], 21);
        assert_eq!(c, 2);
        let (c, _, _) = streaks_from_days(&[18, 19], 21);
        assert_eq!(c, 0);
    }
}
