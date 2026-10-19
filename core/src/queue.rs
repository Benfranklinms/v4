//! Play queue with shuffle, repeat and an autoplay ("from radio") tail.

use crate::db::ArtistAffinity;
use crate::model::*;
use rand::seq::SliceRandom;
use std::collections::HashSet;

#[derive(Default, Clone)]
pub struct Queue {
    pub tracks: Vec<Track>,
    pub original: Vec<Track>,
    pub index: i32,
    pub shuffle: bool,
    pub repeat: u8,
    pub autoplay: bool,
    pub autoplay_from: i32,
    pub source_title: String,
}

impl Queue {
    pub fn new() -> Self {
        Self { index: -1, autoplay: true, autoplay_from: -1, ..Default::default() }
    }

    pub fn state(&self) -> QueueState {
        QueueState {
            tracks: self.tracks.clone(),
            index: self.index,
            shuffle: self.shuffle,
            repeat: self.repeat,
            autoplay: self.autoplay,
            autoplay_from: self.autoplay_from,
            source_title: self.source_title.clone(),
        }
    }

    pub fn current(&self) -> Option<Track> {
        if self.index >= 0 {
            self.tracks.get(self.index as usize).cloned()
        } else {
            None
        }
    }

    pub fn set(&mut self, tracks: Vec<Track>, start: i32, source: String) {
        self.original = tracks.clone();
        self.tracks = tracks;
        self.index = start.clamp(-1, self.tracks.len() as i32 - 1);
        self.autoplay_from = -1;
        self.source_title = source;
        if self.shuffle {
            self.shuffle_rest();
        }
    }

    fn shuffle_rest(&mut self) {
        let start = (self.index + 1).max(0) as usize;
        let end = if self.autoplay_from >= 0 { self.autoplay_from as usize } else { self.tracks.len() };
        if start < end {
            self.tracks[start..end].shuffle(&mut rand::rng());
        }
    }

    pub fn set_shuffle(&mut self, on: bool) {
        if on == self.shuffle {
            return;
        }
        self.shuffle = on;
        if on {
            self.original = self.tracks.clone();
            self.shuffle_rest();
        } else if !self.original.is_empty() {
            let cur = self.current();
            let tail: Vec<Track> = if self.autoplay_from >= 0 { self.tracks[self.autoplay_from as usize..].to_vec() } else { vec![] };
            let mut restored: Vec<Track> = self.original.iter().filter(|t| !tail.iter().any(|x| x.id == t.id)).cloned().collect();
            let ap = restored.len() as i32;
            restored.extend(tail.clone());
            self.tracks = restored;
            if !tail.is_empty() {
                self.autoplay_from = ap;
            }
            if let Some(c) = cur {
                self.index = self.tracks.iter().position(|t| t.id == c.id).map(|i| i as i32).unwrap_or(0);
            }
        }
    }

    pub fn cycle_repeat(&mut self) -> u8 {
        self.repeat = (self.repeat + 1) % 3;
        self.repeat
    }

    /// Advance. `user` = the user pressed next (repeat-one does not hold). Returns None at the end.
    pub fn next(&mut self, user: bool) -> Option<Track> {
        if self.tracks.is_empty() {
            return None;
        }
        if self.repeat == 2 && !user {
            return self.current();
        }
        let n = self.index + 1;
        if (n as usize) < self.tracks.len() {
            self.index = n;
            return self.current();
        }
        if self.repeat >= 1 {
            self.index = 0;
            return self.current();
        }
        None
    }

    pub fn prev(&mut self) -> Option<Track> {
        if self.index > 0 {
            self.index -= 1;
        }
        self.current()
    }

    pub fn jump(&mut self, i: i32) -> Option<Track> {
        if i >= 0 && (i as usize) < self.tracks.len() {
            self.index = i;
        }
        self.current()
    }

    pub fn play_next(&mut self, t: Track) {
        let at = (self.index + 1).max(0) as usize;
        self.tracks.insert(at.min(self.tracks.len()), t);
        if self.autoplay_from >= 0 && at as i32 <= self.autoplay_from {
            self.autoplay_from += 1;
        }
    }

    pub fn add(&mut self, t: Track) {
        if self.autoplay_from >= 0 {
            self.tracks.insert(self.autoplay_from as usize, t);
            self.autoplay_from += 1;
        } else {
            self.tracks.push(t);
        }
        if self.index < 0 {
            self.index = 0;
        }
    }

    pub fn remove(&mut self, i: i32) {
        if i < 0 || i as usize >= self.tracks.len() || i == self.index {
            return;
        }
        self.tracks.remove(i as usize);
        if i < self.index {
            self.index -= 1;
        }
        if self.autoplay_from >= 0 && i < self.autoplay_from {
            self.autoplay_from -= 1;
        }
    }

    pub fn move_item(&mut self, from: i32, to: i32) {
        let len = self.tracks.len() as i32;
        if from < 0 || to < 0 || from >= len || to >= len || from == to {
            return;
        }
        let t = self.tracks.remove(from as usize);
        self.tracks.insert(to as usize, t);
        if self.index == from {
            self.index = to;
        } else if from < self.index && to >= self.index {
            self.index -= 1;
        } else if from > self.index && to <= self.index {
            self.index += 1;
        }
    }

    pub fn remaining(&self) -> usize {
        (self.tracks.len() as i32 - self.index - 1).max(0) as usize
    }

    pub fn append_autoplay(&mut self, tracks: Vec<Track>) -> usize {
        let have: HashSet<String> = self.tracks.iter().map(|t| t.id.clone()).collect();
        let fresh: Vec<Track> = tracks.into_iter().filter(|t| !have.contains(&t.id)).collect();
        if fresh.is_empty() {
            return 0;
        }
        if self.autoplay_from < 0 {
            self.autoplay_from = self.tracks.len() as i32;
        }
        let n = fresh.len();
        self.tracks.extend(fresh);
        n
    }
}

/// Rerank radio candidates using the listener's history: skip what they skip, boost artists they replay.
pub fn rerank(cands: Vec<Track>, aff: &ArtistAffinity, exclude: &HashSet<String>) -> Vec<Track> {
    let mut scored: Vec<(f64, Track)> = cands
        .into_iter()
        .enumerate()
        .filter(|(_, t)| !exclude.contains(&t.id))
        .filter(|(_, t)| aff.track_skips.get(&t.id).copied().unwrap_or(0) < 2)
        .filter(|(_, t)| t.duration_s == 0 || t.duration_s <= 900)
        .map(|(i, t)| {
            let artist = t.main_artist().to_lowercase();
            let plays = aff.plays.get(&artist).copied().unwrap_or(0) as f64;
            let skips = aff.skips.get(&artist).copied().unwrap_or(0) as f64;
            let tplays = aff.track_plays.get(&t.id).copied().unwrap_or(0) as f64;
            let base = 10.0 - (i as f64) * 0.25; // keep YT's own order as the prior
            let artist_score = (plays + 1.0).ln() * 1.6 - (skips / (plays + skips + 1.0)) * 6.0;
            let familiar = if tplays > 0.0 { 1.0 + (tplays.ln_1p()).min(2.0) } else { 0.0 };
            let recent = if aff.recent.contains(&t.id) { -8.0 } else { 0.0 };
            (base + artist_score + familiar + recent, t)
        })
        .collect();
    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    // avoid the same artist back to back
    let mut out: Vec<Track> = Vec::with_capacity(scored.len());
    let mut pending: Vec<Track> = Vec::new();
    for (_, t) in scored {
        if out.last().map(|l: &Track| l.main_artist() == t.main_artist()).unwrap_or(false) {
            pending.push(t);
        } else {
            out.push(t);
            if let Some(p) = pending.iter().position(|p| out.last().map(|l| l.main_artist() != p.main_artist()).unwrap_or(true)) {
                out.push(pending.remove(p));
            }
        }
    }
    out.extend(pending);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    fn t(id: &str, a: &str) -> Track {
        Track { id: id.into(), title: id.into(), artists: a.into(), artist_id: None, album: None, album_id: None, duration_s: 200, thumb: String::new() }
    }
    #[test]
    fn basic_flow() {
        let mut q = Queue::new();
        q.set(vec![t("a", "x"), t("b", "y"), t("c", "z")], 0, "s".into());
        assert_eq!(q.next(false).unwrap().id, "b");
        q.play_next(t("n", "w"));
        assert_eq!(q.next(true).unwrap().id, "n");
        assert_eq!(q.next(true).unwrap().id, "c");
        assert!(q.next(false).is_none());
        q.repeat = 1;
        assert_eq!(q.next(false).unwrap().id, "a");
        q.repeat = 2;
        assert_eq!(q.next(false).unwrap().id, "a");
        assert_eq!(q.next(true).unwrap().id, "b");
    }
    #[test]
    fn shuffle_restores() {
        let mut q = Queue::new();
        let v: Vec<Track> = (0..20).map(|i| t(&i.to_string(), "x")).collect();
        q.set(v.clone(), 0, "s".into());
        q.set_shuffle(true);
        assert_eq!(q.current().unwrap().id, "0");
        assert_eq!(q.tracks.len(), 20);
        q.next(true);
        let cur = q.current().unwrap().id;
        q.set_shuffle(false);
        assert_eq!(q.current().unwrap().id, cur);
        assert_eq!(q.tracks.iter().map(|x| x.id.clone()).collect::<Vec<_>>(), v.iter().map(|x| x.id.clone()).collect::<Vec<_>>());
    }
    #[test]
    fn autoplay_tail_and_add() {
        let mut q = Queue::new();
        q.set(vec![t("a", "x")], 0, "s".into());
        assert_eq!(q.append_autoplay(vec![t("a", "x"), t("r1", "y"), t("r2", "z")]), 2);
        assert_eq!(q.autoplay_from, 1);
        q.add(t("u", "k"));
        assert_eq!(q.tracks[1].id, "u");
        assert_eq!(q.autoplay_from, 2);
    }
    #[test]
    fn rerank_skips_and_boosts() {
        let mut aff = ArtistAffinity { plays: HashMap::new(), skips: HashMap::new(), track_skips: HashMap::new(), track_plays: HashMap::new(), recent: HashSet::new() };
        aff.plays.insert("loved".into(), 40);
        aff.skips.insert("hated".into(), 30);
        aff.track_skips.insert("s".into(), 3);
        let c = vec![t("h", "hated"), t("s", "any"), t("m", "meh"), t("l", "loved")];
        let r = rerank(c, &aff, &HashSet::new());
        assert_eq!(r[0].id, "l");
        assert!(!r.iter().any(|x| x.id == "s"));
        assert_eq!(r.last().unwrap().id, "h");
    }
}
