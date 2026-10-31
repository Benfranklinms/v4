//! Our own parser for the music search response (InnerTube WEB_REMIX `search`).
//!
//! The response has come in two layouts:
//! - older: `musicCardShelfRenderer` (top result) + `musicShelfRenderer` sections (one per kind);
//! - newer: `musicCardShelfRenderer` + many `itemSectionRenderer` entries, each holding
//!   `musicResponsiveListItemRenderer` items whose second column reads "Song • Artist • …".
//!
//! Both are handled, item by item. Each item is classified from its subtitle type word, its
//! navigation endpoint (UC… artist, MPRE… album, VL…/PL… playlist) and its music video type.

use crate::model::*;
use crate::yt::{browse_id_of, parse_duration, runs_text, thumb_of};
use serde_json::Value;

/// Search params for the "Songs" filter.
pub const SONGS_PARAMS: &str = "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ==";

#[derive(Debug, Clone, PartialEq)]
pub enum SearchItem {
    Song(Track),
    Video(Track),
    Artist(Artist),
    Album(Album),
    Playlist(Playlist),
}

#[derive(Debug, Default, Clone)]
pub struct Parsed {
    /// The top-result card's item, when it is something we show.
    pub top: Option<SearchItem>,
    /// Every other item, in page order.
    pub items: Vec<SearchItem>,
    pub corrected_query: Option<String>,
    /// True when the page had a recognised result container at all (even if it was empty).
    pub recognised: bool,
}

impl Parsed {
    pub fn is_empty(&self) -> bool {
        self.top.is_none() && self.items.is_empty()
    }

    /// Fold into the app's result record. Songs and videos both go into `tracks` (songs first
    /// keep page order); the top-result card, when it is a track, leads the list.
    pub fn into_results(self) -> SearchResults {
        let mut out = SearchResults { top: None, tracks: vec![], artists: vec![], albums: vec![], playlists: vec![], corrected_query: self.corrected_query };
        let push = |it: SearchItem, out: &mut SearchResults| match it {
            SearchItem::Song(t) | SearchItem::Video(t) => {
                if !out.tracks.iter().any(|x| x.id == t.id) {
                    out.tracks.push(t)
                }
            }
            SearchItem::Artist(a) => {
                if !out.artists.iter().any(|x| x.id == a.id) {
                    out.artists.push(a)
                }
            }
            SearchItem::Album(a) => {
                if !out.albums.iter().any(|x| x.id == a.id) {
                    out.albums.push(a)
                }
            }
            SearchItem::Playlist(p) => {
                if !out.playlists.iter().any(|x| x.id == p.id) {
                    out.playlists.push(p)
                }
            }
        };
        if let Some(t) = self.top {
            push(t, &mut out);
        }
        for it in self.items {
            push(it, &mut out);
        }
        out.top = out.tracks.first().cloned();
        out
    }

    pub fn tracks(&self) -> Vec<Track> {
        self.top
            .iter()
            .chain(self.items.iter())
            .filter_map(|i| match i {
                SearchItem::Song(t) | SearchItem::Video(t) => Some(t.clone()),
                _ => None,
            })
            .fold(Vec::new(), |mut v, t| {
                if !v.iter().any(|x: &Track| x.id == t.id) {
                    v.push(t)
                }
                v
            })
    }
}

/// Parse a full search response (first page). Tolerates either layout and unknown sections.
pub fn parse_search(v: &Value) -> Parsed {
    let mut out = Parsed::default();
    let sections = v
        .pointer("/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents")
        .or_else(|| v.pointer("/contents/sectionListRenderer/contents"))
        .and_then(|x| x.as_array());
    let Some(sections) = sections else { return out };
    out.recognised = true;
    for sec in sections {
        if let Some(card) = sec.get("musicCardShelfRenderer") {
            if out.top.is_none() {
                out.top = parse_card(card);
            }
            for c in card.get("contents").and_then(|x| x.as_array()).into_iter().flatten() {
                if let Some(it) = c.get("musicResponsiveListItemRenderer").and_then(parse_list_item) {
                    out.items.push(it);
                }
            }
        } else if let Some(shelf) = sec.get("musicShelfRenderer") {
            let shelf_kind = runs_text(shelf.get("title")).to_lowercase();
            for c in shelf.get("contents").and_then(|x| x.as_array()).into_iter().flatten() {
                if let Some(r) = c.get("musicResponsiveListItemRenderer") {
                    if let Some(it) = parse_list_item_hint(r, &shelf_kind) {
                        out.items.push(it);
                    }
                }
            }
        } else if let Some(isr) = sec.get("itemSectionRenderer") {
            for c in isr.get("contents").and_then(|x| x.as_array()).into_iter().flatten() {
                if let Some(r) = c.get("musicResponsiveListItemRenderer") {
                    if let Some(it) = parse_list_item(r) {
                        out.items.push(it);
                    }
                } else if let Some(s) = c.get("showingResultsForRenderer").or_else(|| c.get("didYouMeanRenderer")) {
                    let q = runs_text(s.get("correctedQuery"));
                    if !q.is_empty() && out.corrected_query.is_none() {
                        out.corrected_query = Some(q);
                    }
                }
            }
        }
    }
    out
}

/// Search suggestions (music/get_search_suggestions): the plain text terms.
pub fn parse_suggestions(v: &Value) -> Vec<String> {
    let mut out = Vec::new();
    for sec in v.get("contents").and_then(|x| x.as_array()).into_iter().flatten() {
        let items = sec.pointer("/searchSuggestionsSectionRenderer/contents").and_then(|x| x.as_array());
        for it in items.into_iter().flatten() {
            if let Some(s) = it.get("searchSuggestionRenderer") {
                let t = runs_text(s.get("suggestion"));
                if !t.is_empty() && !out.contains(&t) {
                    out.push(t);
                }
            }
        }
    }
    out
}

fn col<'a>(r: &'a Value, i: usize) -> Option<&'a Value> {
    r.get("flexColumns")?.get(i)?.get("musicResponsiveListItemFlexColumnRenderer")?.get("text")
}

const TYPE_WORDS: &[&str] = &["song", "video", "artist", "album", "single", "ep", "playlist", "profile", "episode", "podcast", "station", "audiobook"];

/// The leading type word of a subtitle ("Song", "Single", …), lowercased, when there is one.
fn type_word(sub: Option<&Value>) -> Option<String> {
    let first = sub?.pointer("/runs/0/text")?.as_str()?.trim().to_lowercase();
    if TYPE_WORDS.contains(&first.as_str()) {
        Some(first)
    } else {
        None
    }
}

fn is_count_or_meta(s: &str) -> bool {
    let l = s.trim().to_lowercase();
    l.ends_with(" views")
        || l.ends_with(" plays")
        || l.ends_with(" songs")
        || l.ends_with(" song")
        || l.ends_with(" subscribers")
        || l.ends_with(" subscriber")
        || l.ends_with(" monthly audience")
        || l.ends_with(" monthly listeners")
        || l.ends_with(" episodes")
        || (l.contains(':') && parse_duration(&l) > 0)
        || (l.len() == 4 && l.chars().all(|c| c.is_ascii_digit()))
}

/// Subtitle segments split on " • ", type word dropped.
fn segments(sub: Option<&Value>) -> Vec<String> {
    let s = runs_text(sub);
    let mut v: Vec<String> = s.split(" • ").map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect();
    if let Some(f) = v.first() {
        if TYPE_WORDS.contains(&f.to_lowercase().as_str()) {
            v.remove(0);
        }
    }
    v
}

struct Links {
    artists: Vec<(String, String)>,
    album: Option<(String, String)>,
    duration: u32,
}

fn links(sub: Option<&Value>) -> Links {
    let mut l = Links { artists: vec![], album: None, duration: 0 };
    for run in sub.and_then(|s| s.get("runs")).and_then(|r| r.as_array()).into_iter().flatten() {
        let txt = run.get("text").and_then(|x| x.as_str()).unwrap_or("").to_string();
        match run.get("navigationEndpoint").and_then(browse_id_of) {
            Some((id, pt)) if pt.contains("ARTIST") || pt.contains("USER_CHANNEL") || (pt.is_empty() && id.starts_with("UC")) => l.artists.push((txt, id)),
            Some((id, pt)) if pt.contains("ALBUM") || id.starts_with("MPRE") => l.album = Some((txt, id)),
            _ => {
                if txt.contains(':') {
                    let d = parse_duration(&txt);
                    if d > 0 {
                        l.duration = d;
                    }
                }
            }
        }
    }
    l
}

fn video_id(r: &Value) -> Option<String> {
    r.pointer("/playlistItemData/videoId")
        .or_else(|| col(r, 0).and_then(|t| t.pointer("/runs/0/navigationEndpoint/watchEndpoint/videoId")))
        .or_else(|| r.pointer("/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId"))
        .and_then(|x| x.as_str())
        .map(|s| s.to_string())
}

fn music_video_type(r: &Value) -> String {
    for p in [
        "/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs/0/navigationEndpoint/watchEndpoint/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType",
        "/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType",
        "/navigationEndpoint/watchEndpoint/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType",
    ] {
        if let Some(s) = r.pointer(p).and_then(|x| x.as_str()) {
            return s.to_string();
        }
    }
    String::new()
}

/// Build a track from a title, a subtitle (one or more columns of runs) and a thumbnail.
fn make_track(id: String, title: String, subs: &[Option<&Value>], thumb: String, fixed_duration: u32) -> Track {
    let mut artists: Vec<(String, String)> = vec![];
    let mut album = None;
    let mut duration = fixed_duration;
    for s in subs {
        let l = links(*s);
        artists.extend(l.artists);
        if album.is_none() {
            album = l.album;
        }
        if duration == 0 {
            duration = l.duration;
        }
    }
    let mut names: Vec<String> = artists.iter().map(|a| a.0.clone()).collect();
    names.dedup();
    if names.is_empty() {
        // unlinked: "Song • Artist • 84M plays" -> first non-meta segment
        if let Some(a) = segments(subs.first().copied().flatten()).into_iter().find(|s| !is_count_or_meta(s) && Some(s.as_str()) != album.as_ref().map(|a: &(String, String)| a.0.as_str())) {
            names.push(a);
        }
    }
    Track {
        id,
        title,
        artists: names.join(", "),
        artist_id: artists.first().map(|a| a.1.clone()),
        album: album.as_ref().map(|a| a.0.clone()),
        album_id: album.map(|a| a.1),
        duration_s: duration,
        thumb,
    }
}

fn parse_list_item(r: &Value) -> Option<SearchItem> {
    parse_list_item_hint(r, "")
}

/// `hint` is the shelf title of the old layout ("songs", "videos", "artists", …) when known.
fn parse_list_item_hint(r: &Value, hint: &str) -> Option<SearchItem> {
    let title = runs_text(col(r, 0)).trim().to_string();
    if title.is_empty() {
        return None;
    }
    let sub = col(r, 1);
    let tw = type_word(sub).unwrap_or_else(|| match hint {
        h if h.contains("song") => "song".into(),
        h if h.contains("video") => "video".into(),
        h if h.contains("artist") => "artist".into(),
        h if h.contains("album") => "album".into(),
        h if h.contains("playlist") => "playlist".into(),
        h if h.contains("episode") || h.contains("podcast") || h.contains("profile") => "skip".into(),
        _ => String::new(),
    });
    if matches!(tw.as_str(), "episode" | "podcast" | "profile" | "station" | "audiobook" | "skip") {
        return None;
    }
    let thumb = r.get("thumbnail").map(thumb_of).unwrap_or_default();
    let mvt = music_video_type(r);
    if mvt.contains("PODCAST") {
        return None;
    }

    if let Some(vid) = video_id(r) {
        // song / video
        let fixed = r
            .pointer("/fixedColumns/0/musicResponsiveListItemFixedColumnRenderer/text")
            .map(|d| parse_duration(&runs_text(Some(d))))
            .unwrap_or(0);
        let mut subs = vec![sub];
        if let Some(c2) = col(r, 2) {
            subs.push(Some(c2));
        }
        let t = make_track(vid, title, &subs, thumb, fixed);
        let is_video = match tw.as_str() {
            "song" => false,
            "video" => true,
            _ => !(mvt.is_empty() || mvt.ends_with("_ATV")) && t.album_id.is_none(),
        };
        return Some(if is_video { SearchItem::Video(t) } else { SearchItem::Song(t) });
    }

    let (bid, pt) = r.get("navigationEndpoint").and_then(browse_id_of)?;
    classify_browse(bid, pt, &tw, title, sub, thumb)
}

fn classify_browse(bid: String, pt: String, tw: &str, title: String, sub: Option<&Value>, thumb: String) -> Option<SearchItem> {
    if bid.starts_with("MPSP") || pt.contains("PODCAST") || pt.contains("NON_MUSIC") {
        return None;
    }
    let segs = segments(sub);
    if pt.contains("ALBUM") || bid.starts_with("MPRE") || matches!(tw, "album" | "single" | "ep") {
        let l = links(sub);
        let artists = if l.artists.is_empty() {
            segs.iter().filter(|s| !is_count_or_meta(s)).cloned().collect::<Vec<_>>().join(", ")
        } else {
            l.artists.iter().map(|a| a.0.clone()).collect::<Vec<_>>().join(", ")
        };
        let year = segs.iter().rev().find_map(|s| if s.len() == 4 { s.parse::<u16>().ok() } else { None });
        let kind = match tw {
            "single" => "Single",
            "ep" => "Ep",
            _ => "Album",
        };
        return Some(SearchItem::Album(Album { id: bid, title, artists, year, thumb, kind: kind.into() }));
    }
    if pt.contains("PLAYLIST") || bid.starts_with("VL") || bid.starts_with("PL") || bid.starts_with("RD") || tw == "playlist" {
        let id = bid.strip_prefix("VL").map(|s| s.to_string()).unwrap_or(bid);
        let count = segs.iter().find_map(|s| {
            let l = s.to_lowercase();
            if l.ends_with(" songs") || l.ends_with(" song") || l.ends_with(" tracks") {
                l.split_whitespace().next()?.replace(',', "").parse::<u64>().ok()
            } else {
                None
            }
        });
        let owner = links(sub).artists.first().map(|a| a.0.clone()).or_else(|| segs.iter().find(|s| !is_count_or_meta(s)).cloned()).unwrap_or_else(|| "YouTube Music".into());
        return Some(SearchItem::Playlist(Playlist { id, title, subtitle: owner, thumb, track_count: count, source: "yt".into() }));
    }
    if pt.contains("ARTIST") || (bid.starts_with("UC") && (tw == "artist" || tw.is_empty())) {
        let subtitle = segs.iter().find(|s| {
            let l = s.to_lowercase();
            l.contains("subscriber") || l.contains("monthly") || l.contains("listener")
        });
        return Some(SearchItem::Artist(Artist { id: bid, name: title, thumb, subtitle: subtitle.cloned() }));
    }
    None
}

/// The top-result card.
fn parse_card(card: &Value) -> Option<SearchItem> {
    let title = runs_text(card.get("title")).trim().to_string();
    if title.is_empty() {
        return None;
    }
    let sub = card.get("subtitle");
    let tw = type_word(sub).unwrap_or_default();
    if matches!(tw.as_str(), "episode" | "podcast" | "profile" | "station" | "audiobook") {
        return None;
    }
    let thumb = card.get("thumbnail").map(thumb_of).unwrap_or_default();
    let nav = card
        .pointer("/title/runs/0/navigationEndpoint")
        .or_else(|| card.get("onTap"))
        .or_else(|| card.pointer("/thumbnailOverlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint"))?;
    if let Some(w) = nav.get("watchEndpoint") {
        let vid = w.get("videoId")?.as_str()?.to_string();
        let mvt = w.pointer("/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType").and_then(|x| x.as_str()).unwrap_or("");
        if mvt.contains("PODCAST") {
            return None;
        }
        let t = make_track(vid, title, &[sub], thumb, 0);
        let is_video = match tw.as_str() {
            "song" => false,
            "video" => true,
            _ => !(mvt.is_empty() || mvt.ends_with("_ATV")) && t.album_id.is_none(),
        };
        return Some(if is_video { SearchItem::Video(t) } else { SearchItem::Song(t) });
    }
    let (bid, pt) = browse_id_of(nav)?;
    classify_browse(bid, pt, &tw, title, sub, thumb)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Value {
        let p = format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"));
        serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
    }

    #[test]
    fn new_layout_item_sections() {
        let p = parse_search(&fixture("search_main_items.json"));
        assert!(p.recognised);
        // top card is the video
        match p.top.as_ref().unwrap() {
            SearchItem::Video(t) => {
                assert_eq!(t.id, "Cz200209hOs");
                assert_eq!(t.artists, "Dominic Fike");
                assert_eq!(t.artist_id.as_deref(), Some("UCsWqHrJvOU_uukQCU92VEvw"));
                assert_eq!(t.duration_s, 145);
            }
            o => panic!("top {o:?}"),
        }
        let r = p.clone().into_results();
        let song = r.tracks.iter().find(|t| t.id == "BqaUS8slDPY").expect("song row");
        assert_eq!(song.title, "WHITE KEYS");
        assert_eq!(song.artists, "Dominic Fike");
        assert!(song.thumb.contains("w544"));
        assert!(p.items.iter().any(|i| matches!(i, SearchItem::Song(t) if t.id == "BqaUS8slDPY")));
        assert!(p.items.iter().any(|i| matches!(i, SearchItem::Video(t) if t.id == "ekjEJBHoU-0")));
        let a = r.artists.iter().find(|a| a.id == "UCsWqHrJvOU_uukQCU92VEvw").expect("artist");
        assert_eq!(a.name, "Dominic Fike");
        assert_eq!(a.subtitle.as_deref(), Some("152M monthly audience"));
        let al = r.albums.iter().find(|a| a.id == "MPREb_Gl0ORKp6moG").expect("single");
        assert_eq!(al.kind, "Single");
        assert_eq!(al.year, Some(2016));
        assert_eq!(al.artists, "Redondo, DeepMe");
        let pl = r.playlists.iter().find(|p| p.id == "PLHjzaXoF3igI").expect("community playlist");
        assert_eq!(pl.subtitle, "styra");
        let yp = r.playlists.iter().find(|p| p.id.starts_with("RDCLAK")).expect("ytm playlist");
        assert_eq!(yp.track_count, Some(143));
        // episodes, podcasts, profiles dropped
        assert!(!r.tracks.iter().any(|t| t.id == "6DwdDK2gkhs"));
        assert!(!r.artists.iter().any(|a| a.id == "UC3B5t2Qcai4TLkD9dxTYLvQ"));
        assert!(!r.playlists.iter().any(|p| p.id.starts_with("MPSP")));
        assert_eq!(r.top.as_ref().unwrap().id, "Cz200209hOs");
    }

    #[test]
    fn old_layout_music_shelf_songs() {
        let p = parse_search(&fixture("search_songs_shelf.json"));
        let t = p.tracks();
        assert!(t.len() >= 3);
        assert_eq!(t[0].id, "BqaUS8slDPY");
        assert_eq!(t[0].artists, "Dominic Fike");
        assert_eq!(t[0].album.as_deref(), Some("WHITE KEYS"));
        assert_eq!(t[0].album_id.as_deref(), Some("MPREb_OSAehzZGQMO"));
        assert_eq!(t[0].duration_s, 145);
        assert!(p.items.iter().all(|i| matches!(i, SearchItem::Song(_))));
    }

    #[test]
    fn suggestions() {
        let s = parse_suggestions(&fixture("search_suggestions.json"));
        assert_eq!(s.first().map(|x| x.as_str()), Some("white keys"));
        assert!(s.len() >= 3);
    }

    #[test]
    fn garbage_is_empty_not_panic() {
        let p = parse_search(&serde_json::json!({"contents": {"foo": 1}}));
        assert!(p.is_empty() && !p.recognised);
        let p = parse_search(&serde_json::json!({"contents": {"tabbedSearchResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [{"itemSectionRenderer": {"contents": [{"musicResponsiveListItemRenderer": {}}]}}]}}}}]}}}));
        assert!(p.is_empty() && p.recognised);
    }
}
