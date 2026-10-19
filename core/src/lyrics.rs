//! Lyrics from LRCLIB (https://lrclib.net). Synced when available; English/Latin-script only, one line per lyric.

use crate::model::*;
use crate::CoreError;
use serde::Deserialize;

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct LrcItem {
    #[serde(default)]
    track_name: String,
    #[serde(default)]
    artist_name: String,
    #[serde(default)]
    duration: f64,
    #[serde(default)]
    instrumental: bool,
    plain_lyrics: Option<String>,
    synced_lyrics: Option<String>,
}

const UA: &str = "iTune/0.1 (personal music player)";

pub async fn fetch(http: &reqwest::Client, t: &Track) -> Result<Option<Lyrics>, CoreError> {
    let title = clean_title(&t.title);
    let artist = t.main_artist();
    let mut cands: Vec<LrcItem> = Vec::new();
    let mut get = http
        .get("https://lrclib.net/api/get")
        .header("User-Agent", UA)
        .query(&[("track_name", title.as_str()), ("artist_name", artist.as_str())]);
    if t.duration_s > 0 {
        get = get.query(&[("duration", t.duration_s.to_string())]);
    }
    if let Some(al) = &t.album {
        get = get.query(&[("album_name", al.as_str())]);
    }
    if let Ok(r) = get.send().await {
        if r.status().is_success() {
            if let Ok(i) = r.json::<LrcItem>().await {
                cands.push(i);
            }
        }
    }
    if cands.iter().all(|c| c.synced_lyrics.is_none()) {
        let r = http
            .get("https://lrclib.net/api/search")
            .header("User-Agent", UA)
            .query(&[("track_name", title.as_str()), ("artist_name", artist.as_str())])
            .send()
            .await
            .map_err(|e| CoreError::Network { msg: e.to_string() })?;
        if r.status().is_success() {
            if let Ok(v) = r.json::<Vec<LrcItem>>().await {
                cands.extend(v);
            }
        }
    }
    Ok(choose(cands, t.duration_s))
}

fn choose(cands: Vec<LrcItem>, duration_s: u32) -> Option<Lyrics> {
    let mut best: Option<(i32, Lyrics)> = None;
    for c in cands {
        if c.instrumental {
            continue;
        }
        let dur_ok = duration_s == 0 || c.duration == 0.0 || (c.duration - duration_s as f64).abs() <= 4.0;
        let (lyr, synced) = if let Some(s) = c.synced_lyrics.as_deref().filter(|s| !s.trim().is_empty()) {
            (parse_lrc(s, duration_s * 1000), true)
        } else if let Some(p) = c.plain_lyrics.as_deref().filter(|s| !s.trim().is_empty()) {
            (plain(p), false)
        } else {
            continue;
        };
        if lyr.is_empty() || !is_latin(&lyr) {
            continue; // English only: skip non-Latin-script lyric sets
        }
        let score = (synced as i32) * 10 + (dur_ok as i32) * 5 - (c.track_name.is_empty() as i32) - (c.artist_name.is_empty() as i32);
        if best.as_ref().map(|(s, _)| score > *s).unwrap_or(true) {
            best = Some((score, Lyrics { synced, lines: lyr, source: "LRCLIB".into() }));
        }
    }
    best.map(|b| b.1)
}

fn is_latin(lines: &[LyricLine]) -> bool {
    let mut latin = 0usize;
    let mut other = 0usize;
    for l in lines {
        for ch in l.text.chars() {
            if ch.is_alphabetic() {
                if (ch as u32) < 0x0250 {
                    latin += 1;
                } else {
                    other += 1;
                }
            }
        }
    }
    latin > 0 && other * 5 < latin
}

pub fn clean_title(t: &str) -> String {
    let mut s = t.to_string();
    for pat in ["(feat", "(ft", "[feat", "(from", "(From", "(Official", "(Lyric", "(Audio", "(Video", " - From", "(With"] {
        if let Some(i) = s.find(pat) {
            s.truncate(i);
        }
    }
    s.trim().to_string()
}

pub fn parse_lrc(src: &str, total_ms: u32) -> Vec<LyricLine> {
    let mut raw: Vec<(u32, String)> = Vec::new();
    for line in src.lines() {
        let mut rest = line.trim();
        let mut stamps = Vec::new();
        while rest.starts_with('[') {
            let Some(end) = rest.find(']') else { break };
            let tag = &rest[1..end];
            if let Some(ms) = parse_stamp(tag) {
                stamps.push(ms);
            }
            rest = rest[end + 1..].trim_start();
        }
        let text = rest.trim().to_string();
        for s in stamps {
            raw.push((s, text.clone()));
        }
    }
    raw.sort_by_key(|x| x.0);
    let mut out = Vec::new();
    for i in 0..raw.len() {
        let (start, ref text) = raw[i];
        if text.is_empty() {
            continue;
        }
        let next = raw.get(i + 1).map(|x| x.0).unwrap_or(if total_ms > start { total_ms } else { start + 5000 });
        let end = next.max(start + 300).min(start + 12_000);
        out.push(LyricLine { start_ms: start, end_ms: end, text: text.clone(), words: words(text, start, end) });
    }
    out
}

fn parse_stamp(tag: &str) -> Option<u32> {
    let (m, s) = tag.split_once(':')?;
    let m: u32 = m.trim().parse().ok()?;
    let s: f64 = s.trim().parse().ok()?;
    Some(m * 60_000 + (s * 1000.0).round() as u32)
}

/// Word timings spread over the line by character weight (LRCLIB only has line sync).
pub fn words(text: &str, start: u32, end: u32) -> Vec<LyricWord> {
    let ws: Vec<&str> = text.split_whitespace().collect();
    if ws.is_empty() {
        return vec![];
    }
    // sing through ~85% of the gap, the rest is a breath
    let span = ((end - start) as f64 * 0.85).max(200.0);
    let total: f64 = ws.iter().map(|w| w.chars().count() as f64 + 1.5).sum();
    let mut t = start as f64;
    ws.iter()
        .map(|w| {
            let d = span * (w.chars().count() as f64 + 1.5) / total;
            let wd = LyricWord { text: w.to_string(), start_ms: t as u32, end_ms: (t + d) as u32 };
            t += d;
            wd
        })
        .collect()
}

fn plain(src: &str) -> Vec<LyricLine> {
    src.lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty())
        .map(|l| LyricLine { start_ms: 0, end_ms: 0, text: l.to_string(), words: vec![] })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lrc() {
        let l = parse_lrc("[00:01.50] Hello world\n[00:04.00]\n[00:05.00][00:09.00] Again\n", 12_000);
        assert_eq!(l.len(), 3);
        assert_eq!(l[0].start_ms, 1500);
        assert_eq!(l[0].end_ms, 4000);
        assert_eq!(l[0].words.len(), 2);
        assert!(l[0].words[1].start_ms > l[0].words[0].start_ms);
        assert_eq!(l[2].start_ms, 9000);
        assert_eq!(l[2].end_ms, 12_000);
        assert!(is_latin(&l));
    }
    #[test]
    fn non_latin_rejected() {
        let l = parse_lrc("[00:01.00] മഴയിൽ ഞാൻ\n", 5000);
        assert!(!is_latin(&l));
    }
    #[test]
    fn cleans() {
        assert_eq!(clean_title("Illuminati (From \"Aavesham\")"), "Illuminati");
        assert_eq!(clean_title("Song (feat. X)"), "Song");
    }
}
