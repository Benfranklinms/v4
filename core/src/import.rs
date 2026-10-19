//! Playlist import from public Spotify / Apple Music links, matched to YT Music tracks.

use crate::model::*;
use crate::yt::Yt;
use crate::CoreError;
use regex::Regex;
use serde_json::Value;
use std::collections::HashSet;
use std::sync::Arc;

const BROWSER_UA: &str =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";

pub struct Parsed {
    pub name: String,
    pub service: String,
    pub cover: String,
    pub tracks: Vec<ImportedTrack>,
}

pub async fn fetch_playlist(http: &reqwest::Client, url: &str) -> Result<Parsed, CoreError> {
    let u = url.trim();
    if u.contains("spotify.com") || u.starts_with("spotify:") {
        spotify(http, u).await
    } else if u.contains("music.apple.com") {
        apple(http, u).await
    } else {
        Err(CoreError::Invalid { msg: "Paste a public Spotify or Apple Music playlist link".into() })
    }
}

async fn get_text(http: &reqwest::Client, url: &str) -> Result<String, CoreError> {
    let r = http
        .get(url)
        .header("User-Agent", BROWSER_UA)
        .header("Accept-Language", "en-US,en;q=0.9")
        .send()
        .await
        .map_err(|e| CoreError::Network { msg: e.to_string() })?;
    if !r.status().is_success() {
        return Err(CoreError::Network { msg: format!("HTTP {}", r.status().as_u16()) });
    }
    r.text().await.map_err(|e| CoreError::Network { msg: e.to_string() })
}

async fn spotify(http: &reqwest::Client, u: &str) -> Result<Parsed, CoreError> {
    let re = Regex::new(r"(playlist|album)[/:]([A-Za-z0-9]{10,})").unwrap();
    let c = re.captures(u).ok_or(CoreError::Invalid { msg: "Not a Spotify playlist link".into() })?;
    let (kind, id) = (c[1].to_string(), c[2].to_string());
    let html = get_text(http, &format!("https://open.spotify.com/embed/{kind}/{id}")).await?;
    parse_spotify_embed(&html)
}

pub fn parse_spotify_embed(html: &str) -> Result<Parsed, CoreError> {
    let re = Regex::new(r#"(?s)<script id="__NEXT_DATA__" type="application/json">(.*?)</script>"#).unwrap();
    let j = re.captures(html).ok_or(CoreError::Invalid { msg: "Spotify page format changed".into() })?;
    let v: Value = serde_json::from_str(&j[1]).map_err(|e| CoreError::Invalid { msg: e.to_string() })?;
    let ent = v
        .pointer("/props/pageProps/state/data/entity")
        .ok_or(CoreError::Invalid { msg: "Playlist is private or unavailable".into() })?;
    let name = ent.get("name").or(ent.get("title")).and_then(|x| x.as_str()).unwrap_or("Imported").to_string();
    let cover = ent
        .pointer("/coverArt/sources/0/url")
        .or(ent.pointer("/visualIdentity/image/0/url"))
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .to_string();
    let mut tracks = vec![];
    if let Some(list) = ent.get("trackList").and_then(|x| x.as_array()) {
        for t in list {
            let title = t.get("title").and_then(|x| x.as_str()).unwrap_or("").to_string();
            if title.is_empty() {
                continue;
            }
            tracks.push(ImportedTrack {
                title,
                artists: t.get("subtitle").and_then(|x| x.as_str()).unwrap_or("").replace('\u{a0}', " "),
                duration_s: (t.get("duration").and_then(|x| x.as_u64()).unwrap_or(0) / 1000) as u32,
            });
        }
    }
    Ok(Parsed { name, service: "Spotify".into(), cover, tracks })
}

async fn apple(http: &reqwest::Client, u: &str) -> Result<Parsed, CoreError> {
    let html = get_text(http, u).await?;
    parse_apple(&html)
}

pub fn parse_apple(html: &str) -> Result<Parsed, CoreError> {
    let re = Regex::new(r#"(?s)<script type="application/json" id="serialized-server-data">(.*?)</script>"#).unwrap();
    let j = re.captures(html).ok_or(CoreError::Invalid { msg: "Apple Music page format changed".into() })?;
    let v: Value = serde_json::from_str(&j[1]).map_err(|e| CoreError::Invalid { msg: e.to_string() })?;
    let mut tracks = vec![];
    let mut seen = HashSet::new();
    walk_apple(&v, &mut tracks, &mut seen, 0);
    let og = |p: &str| -> String {
        let r = Regex::new(&format!(r#"<meta (?:property|name)="{p}" content="([^"]*)""#)).unwrap();
        r.captures(html).map(|c| html_unescape(&c[1])).unwrap_or_default()
    };
    let mut name = og("og:title");
    for suf in [" on Apple Music", " - Apple Music"] {
        if let Some(i) = name.find(suf) {
            name.truncate(i);
        }
    }
    if let Some(i) = name.rfind(" by ") {
        if name.len() - i < 40 {
            name.truncate(i);
        }
    }
    Ok(Parsed { name: if name.is_empty() { "Imported".into() } else { name }, service: "Apple Music".into(), cover: og("og:image"), tracks })
}

fn walk_apple(v: &Value, out: &mut Vec<ImportedTrack>, seen: &mut HashSet<String>, depth: u32) {
    if depth > 14 {
        return;
    }
    match v {
        Value::Object(o) => {
            let is_song = o.get("contentDescriptor").and_then(|c| c.get("kind")).and_then(|k| k.as_str()) == Some("song");
            if is_song && o.contains_key("title") && o.contains_key("subtitleLinks") {
                let id = o.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                if seen.insert(id) {
                    let artists = o
                        .get("subtitleLinks")
                        .and_then(|x| x.as_array())
                        .map(|a| a.iter().filter_map(|l| l.get("title").and_then(|t| t.as_str())).collect::<Vec<_>>().join(", "))
                        .unwrap_or_default();
                    out.push(ImportedTrack {
                        title: o["title"].as_str().unwrap_or("").to_string(),
                        artists,
                        duration_s: (o.get("duration").and_then(|d| d.as_u64()).unwrap_or(0) / 1000) as u32,
                    });
                }
                return;
            }
            for x in o.values() {
                walk_apple(x, out, seen, depth + 1);
            }
        }
        Value::Array(a) => {
            for x in a {
                walk_apple(x, out, seen, depth + 1);
            }
        }
        _ => {}
    }
}

fn html_unescape(s: &str) -> String {
    s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}

pub fn norm(s: &str) -> String {
    let s = s.to_lowercase();
    let mut out = String::new();
    let mut depth = 0;
    for ch in s.chars() {
        match ch {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth > 0 => {}
            c if c.is_alphanumeric() => out.push(c),
            _ => out.push(' '),
        }
    }
    let out = out.replace(" feat ", " ").replace(" ft ", " ");
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn tokens(s: &str) -> HashSet<String> {
    norm(s).split(' ').filter(|w| !w.is_empty()).map(|w| w.to_string()).collect()
}

fn jaccard(a: &HashSet<String>, b: &HashSet<String>) -> f64 {
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let i = a.intersection(b).count() as f64;
    i / (a.len() + b.len()) as f64 * 2.0
}

pub fn score(src: &ImportedTrack, t: &Track) -> u32 {
    let ts = jaccard(&tokens(&src.title), &tokens(&t.title));
    let exact = norm(&src.title) == norm(&t.title);
    let sa = tokens(&src.artists);
    let ta = tokens(&t.artists);
    let as_ = if sa.is_empty() { 0.5 } else { (sa.intersection(&ta).count() as f64 / sa.len().min(ta.len().max(1)) as f64).min(1.0) };
    let dur = if src.duration_s == 0 || t.duration_s == 0 {
        0.6
    } else {
        let d = (src.duration_s as i64 - t.duration_s as i64).abs();
        if d <= 3 { 1.0 } else if d <= 10 { 0.75 } else if d <= 30 { 0.35 } else { 0.0 }
    };
    let s = (if exact { 1.0 } else { ts }) * 50.0 + as_ * 30.0 + dur * 20.0;
    s.round().clamp(0.0, 100.0) as u32
}

pub async fn match_all(yt: Arc<Yt>, tracks: Vec<ImportedTrack>) -> Vec<ImportMatch> {
    let sem = Arc::new(tokio::sync::Semaphore::new(6));
    let mut set = tokio::task::JoinSet::new();
    for (i, src) in tracks.into_iter().enumerate() {
        let yt = yt.clone();
        let sem = sem.clone();
        set.spawn(async move {
            let _p = sem.acquire().await;
            let q = format!("{} {}", src.title, src.artists.split(',').next().unwrap_or(""));
            let cands = yt.search_tracks(&q).await.unwrap_or_default();
            let best = cands.into_iter().take(8).map(|t| (score(&src, &t), t)).max_by_key(|x| x.0);
            let (confidence, matched) = match best {
                Some((s, t)) => (s, Some(t)),
                None => (0, None),
            };
            let status = if confidence >= 72 { "matched" } else if confidence >= 45 { "review" } else { "missing" };
            (i, ImportMatch { source: src, matched: if confidence >= 45 { matched } else { None }, confidence, status: status.into() })
        });
    }
    let mut out = Vec::new();
    while let Some(r) = set.join_next().await {
        if let Ok(x) = r {
            out.push(x);
        }
    }
    out.sort_by_key(|x| x.0);
    out.into_iter().map(|x| x.1).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn tr(title: &str, artists: &str, d: u32) -> Track {
        Track { id: "x".into(), title: title.into(), artists: artists.into(), artist_id: None, album: None, album_id: None, duration_s: d, thumb: String::new() }
    }
    #[test]
    fn scoring() {
        let src = ImportedTrack { title: "Blinding Lights".into(), artists: "The Weeknd".into(), duration_s: 200 };
        assert!(score(&src, &tr("Blinding Lights", "The Weeknd", 201)) >= 95);
        assert!(score(&src, &tr("Blinding Lights (Acoustic)", "Matt Johnson", 260)) < 72);
        let src2 = ImportedTrack { title: "expectations (feat. Chappell Roan)".into(), artists: "Olivia Rodrigo, Chappell Roan".into(), duration_s: 224 };
        assert!(score(&src2, &tr("expectations", "Olivia Rodrigo & Chappell Roan", 224)) >= 72);
    }
    #[test]
    fn spotify_embed_parse() {
        let html = r#"<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Mix","coverArt":{"sources":[{"url":"https://i/x"}]},"trackList":[{"title":"A","subtitle":"B,\u00a0C","duration":180000}]}}}}}}</script>"#;
        let p = parse_spotify_embed(html).unwrap();
        assert_eq!(p.name, "Mix");
        assert_eq!(p.tracks[0].artists, "B, C");
        assert_eq!(p.tracks[0].duration_s, 180);
    }
}
