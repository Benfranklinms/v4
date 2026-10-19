//! Network tests against the real services. Run with LIVE=1 (CI); skipped otherwise.

use super::*;

fn live() -> bool {
    std::env::var("LIVE").map(|v| v == "1").unwrap_or(false)
}

fn core() -> Arc<Core> {
    let dir = std::env::temp_dir().join(format!("itune-live-{}", rand::random::<u32>()));
    Core::new(dir.to_string_lossy().to_string(), "android".into()).unwrap()
}

#[tokio::test]
async fn live_search() {
    if !live() {
        return;
    }
    let c = core();
    let r = c.search("Blinding Lights The Weeknd".into()).await.unwrap();
    assert!(!r.tracks.is_empty(), "no tracks");
    assert!(r.tracks.iter().any(|t| t.title.to_lowercase().contains("blinding")));
    println!("search ok: {} tracks, first {} - {}", r.tracks.len(), r.tracks[0].title, r.tracks[0].artists);
}

#[tokio::test]
async fn live_lyrics() {
    if !live() {
        return;
    }
    let c = core();
    let t = Track {
        id: "J7p4bzqLvCw".into(),
        title: "Blinding Lights".into(),
        artists: "The Weeknd".into(),
        artist_id: None,
        album: Some("After Hours".into()),
        album_id: None,
        duration_s: 201,
        thumb: String::new(),
    };
    let l = c.lyrics(t).await.unwrap().expect("lyrics");
    assert!(l.synced && l.lines.len() > 10);
    println!("lyrics ok: {} lines", l.lines.len());
}

/// Stream resolution depends on the runner's IP reputation with YouTube; it reports instead of failing
/// unless STRICT_STREAM=1.
#[tokio::test]
async fn live_stream_and_radio() {
    if !live() {
        return;
    }
    let c = core();
    let strict = std::env::var("STRICT_STREAM").map(|v| v == "1").unwrap_or(false);
    for platform_mp4 in [false, true] {
        let dir = std::env::temp_dir().join(format!("itune-live-s{}", rand::random::<u32>()));
        let c2 = Core::new(dir.to_string_lossy().to_string(), if platform_mp4 { "macos" } else { "android" }.into()).unwrap();
        match c2.stream("J7p4bzqLvCw".into()).await {
            Ok(s) => {
                let r = reqwest::Client::new().get(&s.url).header("User-Agent", &s.user_agent).header("Range", "bytes=0-4095").send().await;
                let code = r.map(|r| r.status().as_u16()).unwrap_or(0);
                println!("STREAM mp4={platform_mp4} client={} mime={} hls={} -> range GET {code}", s.client, s.mime, s.is_hls);
                if strict {
                    assert!(code == 200 || code == 206);
                }
            }
            Err(e) => {
                println!("STREAM mp4={platform_mp4} FAILED: {e}");
                if strict {
                    panic!("{e}");
                }
            }
        }
    }
    match c.radio("J7p4bzqLvCw".into()).await {
        Ok(r) => println!("RADIO ok: {} tracks", r.len()),
        Err(e) => println!("RADIO FAILED: {e}"),
    }
}

#[tokio::test]
async fn live_import_spotify() {
    if !live() {
        return;
    }
    let c = core();
    match c.import_playlist("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M".into()).await {
        Ok(r) => {
            let m = r.matches.iter().filter(|m| m.status == "matched").count();
            println!("IMPORT {}: {} tracks, {} matched", r.name, r.matches.len(), m);
            assert!(r.matches.len() >= 20);
        }
        Err(e) => panic!("{e}"),
    }
}

#[tokio::test]
async fn offline_core_flow() {
    // No network: db, likes, playlists, stats.
    let c = core();
    let t = Track { id: "a".into(), title: "A".into(), artists: "X".into(), artist_id: None, album: None, album_id: None, duration_s: 200, thumb: String::new() };
    c.set_liked(t.clone(), true).unwrap();
    assert!(c.is_liked("a".into()));
    let p = c.create_playlist("Mine".into(), vec![t.clone()]).unwrap();
    c.add_to_playlist(p.id.clone(), t.clone()).unwrap();
    assert_eq!(c.playlists().unwrap()[0].track_count, Some(2));
    c.record_play(t.clone(), now() - 100, 180_000, false, 330, "test".into()).unwrap();
    c.record_play(t.clone(), now() - 50, 10_000, true, 330, "test".into()).unwrap();
    let s = c.stats("week".into(), 330).unwrap();
    assert_eq!(s.plays, 1);
    assert_eq!(s.minutes, 3);
    assert_eq!(s.streak_days, 1);
    assert_eq!(s.top_artists[0].name, "X");
    assert_eq!(c.recent(5).unwrap().len(), 1);
    let first = c.queue_set(vec![t.clone()], 0, "x".into());
    assert_eq!(first.unwrap().id, "a");
    assert_eq!(c.library_counts().liked, 1);
}

#[tokio::test]
async fn live_home() {
    if !live() {
        return;
    }
    let c = core();
    let h = c.home(330).await.unwrap();
    println!("home: {} shelves, {} mixes, {} trending", h.shelves.len(), h.mixes.len(), h.trending.len());
    for s in &h.shelves {
        println!("  shelf {} [{}] {}", s.title, s.layout, s.items.len());
    }
    assert!(!h.shelves.is_empty() || !h.trending.is_empty(), "home feed empty");
}

/// Regression: lastfm_state() used to lock the Last.fm mutex twice in one expression (self-deadlock).
#[test]
fn lastfm_state_does_not_deadlock() {
    let c = core();
    c.lastfm_configure("k".into(), "s".into());
    let (tx, rx) = std::sync::mpsc::channel();
    let c2 = c.clone();
    std::thread::spawn(move || {
        let st = c2.lastfm_state();
        let _ = tx.send(st.available);
    });
    assert_eq!(rx.recv_timeout(std::time::Duration::from_secs(5)), Ok(true), "lastfm_state deadlocked");
}
