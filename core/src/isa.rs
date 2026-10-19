//! Isa: the on-device DJ. Rule-based prompt parsing into search seeds and queue constraints.

use crate::model::IsaPlan;

struct Mood {
    key: &'static str,
    words: &'static [&'static str],
    seeds: &'static [&'static str],
    energy: i32,
}

const MOODS: &[Mood] = &[
    Mood { key: "night drive", words: &["drive", "driving", "night drive", "highway", "road"], seeds: &["night drive", "synthwave drive"], energy: 1 },
    Mood { key: "rainy", words: &["rain", "rainy", "monsoon", "storm", "mazha"], seeds: &["rainy day", "monsoon"], energy: -1 },
    Mood { key: "late night", words: &["night", "midnight", "late", "3am", "2am"], seeds: &["late night"], energy: -1 },
    Mood { key: "lo-fi", words: &["lofi", "lo-fi", "lo fi", "chillhop"], seeds: &["lofi"], energy: -2 },
    Mood { key: "focus", words: &["focus", "study", "work", "coding", "concentrate", "deep work"], seeds: &["focus", "instrumental study"], energy: -1 },
    Mood { key: "chill", words: &["chill", "relax", "calm", "mellow", "soft", "slow"], seeds: &["chill", "acoustic chill"], energy: -2 },
    Mood { key: "sleep", words: &["sleep", "bed", "ambient", "dream"], seeds: &["sleep ambient"], energy: -3 },
    Mood { key: "workout", words: &["gym", "workout", "run", "running", "pump", "cardio"], seeds: &["workout", "gym motivation"], energy: 3 },
    Mood { key: "party", words: &["party", "dance", "club", "banger", "hype", "kuthu"], seeds: &["party hits", "dance"], energy: 3 },
    Mood { key: "happy", words: &["happy", "upbeat", "sunny", "good vibes", "feel good"], seeds: &["feel good", "upbeat"], energy: 2 },
    Mood { key: "sad", words: &["sad", "heartbreak", "breakup", "cry", "lonely", "melancholy"], seeds: &["sad songs", "heartbreak"], energy: -2 },
    Mood { key: "romantic", words: &["love", "romantic", "romance", "date"], seeds: &["romantic", "love songs"], energy: -1 },
    Mood { key: "morning", words: &["morning", "sunrise", "coffee", "breakfast"], seeds: &["morning acoustic"], energy: 0 },
    Mood { key: "throwback", words: &["throwback", "old", "nostalgia", "90s", "2000s", "retro", "classic"], seeds: &["throwback hits"], energy: 0 },
    Mood { key: "indie", words: &["indie"], seeds: &["indie"], energy: 0 },
    Mood { key: "hip-hop", words: &["rap", "hip hop", "hip-hop", "trap"], seeds: &["hip hop"], energy: 2 },
    Mood { key: "rock", words: &["rock", "metal", "guitar"], seeds: &["rock"], energy: 2 },
    Mood { key: "jazz", words: &["jazz", "blues", "soul"], seeds: &["jazz"], energy: -1 },
    Mood { key: "classical", words: &["classical", "piano", "orchestra", "carnatic"], seeds: &["piano"], energy: -2 },
    Mood { key: "edm", words: &["edm", "electronic", "house", "techno"], seeds: &["electronic"], energy: 3 },
];

const LANGS: &[(&str, &[&str])] = &[
    ("Malayalam", &["malayalam", "mallu", "mollywood"]),
    ("Tamil", &["tamil", "kollywood"]),
    ("Hindi", &["hindi", "bollywood"]),
    ("Telugu", &["telugu", "tollywood"]),
    ("Kannada", &["kannada"]),
    ("Punjabi", &["punjabi"]),
    ("English", &["english"]),
    ("Korean", &["korean", "k-pop", "kpop"]),
    ("Spanish", &["spanish", "latin", "reggaeton"]),
    ("Japanese", &["japanese", "j-pop", "anime"]),
];

fn has(p: &str, w: &str) -> bool {
    // word-ish match
    let p = format!(" {} ", p);
    p.contains(&format!(" {} ", w)) || (w.contains(' ') && p.contains(w)) || (w.len() > 4 && p.contains(w))
}

pub fn plan(prompt: &str) -> IsaPlan {
    let raw = prompt.trim();
    let p: String = raw
        .to_lowercase()
        .chars()
        .map(|c| if c.is_alphanumeric() || c == '-' || c == ' ' { c } else { ' ' })
        .collect();
    let p = p.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut moods: Vec<&Mood> = MOODS.iter().filter(|m| m.words.iter().any(|w| has(&p, w))).collect();
    let languages: Vec<String> = LANGS
        .iter()
        .filter(|(_, ws)| ws.iter().any(|w| has(&p, w)))
        .map(|(n, _)| n.to_string())
        .collect();
    let avoid_vocals = ["no vocals", "instrumental", "without vocals", "no lyrics", "no singing"].iter().any(|w| p.contains(w));
    let max_duration_s = if p.contains("short") { Some(210) } else { None };
    // "like <artist>" / "similar to <artist>" / "by <artist>"
    let mut artist_seed: Option<String> = None;
    for key in ["similar to ", "sounds like ", "like ", "by "] {
        if let Some(i) = p.find(key) {
            let rest = &p[i + key.len()..];
            let name: String = rest.split([',', '+']).next().unwrap_or("").split(" and ").next().unwrap_or("").trim().to_string();
            let name = name.split_whitespace().take(4).collect::<Vec<_>>().join(" ");
            if !name.is_empty() && !MOODS.iter().any(|m| m.words.contains(&name.as_str())) {
                artist_seed = Some(name);
                break;
            }
        }
    }
    if moods.is_empty() && languages.is_empty() && artist_seed.is_none() {
        // Unknown prompt: use it verbatim as a search seed.
        return IsaPlan {
            title: title_case(raw),
            moods: vec![],
            languages: vec![],
            seeds: vec![raw.to_string(), format!("{raw} songs")],
            avoid_vocals,
            max_duration_s,
            energy: 0,
            explanation: format!("Searching for \"{raw}\" and building a radio from the best match."),
        };
    }
    moods.truncate(3);
    let energy = if moods.is_empty() { 0 } else { moods.iter().map(|m| m.energy).sum::<i32>() / moods.len() as i32 };
    let mut seeds: Vec<String> = Vec::new();
    let mood_word = moods.first().map(|m| m.seeds[0]).unwrap_or("");
    let langs_or_none: Vec<Option<&String>> =
        if languages.is_empty() { vec![None] } else { languages.iter().map(Some).collect() };
    for l in &langs_or_none {
        for m in moods.iter() {
            for s in m.seeds.iter().take(1) {
                seeds.push(match l {
                    Some(l) => format!("{l} {s} songs"),
                    None => format!("{s} songs"),
                });
            }
        }
        if moods.is_empty() {
            if let Some(l) = l {
                seeds.push(format!("{l} hits"));
            }
        }
    }
    if let Some(a) = &artist_seed {
        seeds.insert(0, format!("{a} {mood_word}").trim().to_string());
    }
    if avoid_vocals {
        seeds = seeds.into_iter().map(|s| format!("{s} instrumental")).collect();
    }
    seeds.dedup();
    seeds.truncate(6);
    let mut parts: Vec<String> = moods.iter().map(|m| m.key.to_string()).collect();
    parts.extend(languages.iter().cloned());
    if let Some(a) = &artist_seed {
        parts.push(format!("like {}", title_case(a)));
    }
    let title = title_case(&parts.join(" · "));
    let explanation = format!(
        "{}{}{}{}",
        if moods.is_empty() { String::new() } else { format!("Mood: {}. ", moods.iter().map(|m| m.key).collect::<Vec<_>>().join(", ")) },
        if languages.is_empty() { String::new() } else { format!("Languages: {}, mixed evenly. ", languages.join(", ")) },
        if avoid_vocals { "Instrumentals only. " } else { "" },
        match energy {
            e if e >= 2 => "High energy.",
            e if e <= -2 => "Low energy, slower tracks.",
            _ => "Medium energy.",
        }
    );
    IsaPlan {
        title,
        moods: moods.iter().map(|m| m.key.to_string()).collect(),
        languages,
        seeds,
        avoid_vocals,
        max_duration_s,
        energy,
        explanation,
    }
}

fn title_case(s: &str) -> String {
    s.split(' ')
        .map(|w| {
            let mut c = w.chars();
            match c.next() {
                Some(f) if w != "·" => f.to_uppercase().collect::<String>() + c.as_str(),
                _ => w.to_string(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

const ARTIST_GENRES: &[(&str, &[&str])] = &[
    ("Malayalam film", &["sushin shyam", "hesham abdul wahab", "vineeth sreenivasan", "gopi sundar", "jakes bejoy", "dabzee",
        "k s chithra", "k. s. chithra", "shaan rahman", "bijibal", "prashant pillai", "vidyasagar", "m. jayachandran", "sooraj s kurup",
        "justin varghese", "rex vijayan", "sithara", "haricharan", "vineeth", "sid sriram & sushin", "dulquer"]),
    ("Tamil film", &["anirudh", "a. r. rahman", "a.r. rahman", "ar rahman", "yuvan shankar raja", "harris jayaraj", "g. v. prakash",
        "santhosh narayanan", "d. imman", "ilaiyaraaja", "sid sriram", "hiphop tamizha", "sean roldan"]),
    ("Hindi film", &["arijit singh", "pritam", "shreya ghoshal", "vishal-shekhar", "atif aslam", "jubin nautiyal", "neha kakkar",
        "kk", "sonu nigam", "badshah", "amit trivedi", "sachin-jigar"]),
    ("Telugu film", &["devi sri prasad", "thaman s", "s. thaman", "m. m. keeravani", "sid sriram"]),
    ("Pop", &["the weeknd", "taylor swift", "dua lipa", "ed sheeran", "billie eilish", "ariana grande", "justin bieber", "olivia rodrigo",
        "harry styles", "sabrina carpenter", "bruno mars", "coldplay", "sza", "charlie puth", "lady gaga", "rihanna", "katy perry"]),
    ("Hip-hop", &["drake", "kendrick lamar", "travis scott", "eminem", "kanye west", "j. cole", "post malone", "21 savage", "future",
        "lil baby", "tyler, the creator", "dabzee", "divine", "seedhe maut", "kr$na"]),
    ("Rock", &["arctic monkeys", "linkin park", "imagine dragons", "queen", "nirvana", "foo fighters", "radiohead", "the beatles",
        "red hot chili peppers", "green day"]),
    ("Lo-fi", &["lofi girl", "chillhop", "lo-fi"]),
    ("K-pop", &["bts", "blackpink", "newjeans", "stray kids", "twice", "jungkook", "le sserafim", "aespa"]),
    ("Electronic", &["daft punk", "avicii", "calvin harris", "martin garrix", "the chainsmokers", "kygo", "alan walker", "marshmello"]),
];

/// Coarse genre for stats and Isa filtering.
pub fn genre_of(title: &str, artist: &str, album: &str) -> String {
    let a = artist.to_lowercase();
    let t = format!("{} {}", title.to_lowercase(), album.to_lowercase());
    if t.contains("lofi") || t.contains("lo-fi") || t.contains("lo fi") {
        return "Lo-fi".into();
    }
    for (g, names) in ARTIST_GENRES {
        if names.iter().any(|n| a.contains(n)) {
            return g.to_string();
        }
    }
    let all = format!("{title}{artist}{album}");
    for ch in all.chars() {
        let c = ch as u32;
        if (0x0D00..=0x0D7F).contains(&c) {
            return "Malayalam film".into();
        }
        if (0x0B80..=0x0BFF).contains(&c) {
            return "Tamil film".into();
        }
        if (0x0900..=0x097F).contains(&c) {
            return "Hindi film".into();
        }
        if (0xAC00..=0xD7AF).contains(&c) {
            return "K-pop".into();
        }
    }
    if t.contains("remix") || t.contains(" edm") {
        return "Electronic".into();
    }
    if t.contains("instrumental") || t.contains("piano") {
        return "Instrumental".into();
    }
    "Other".into()
}

/// True when the track looks like it fits the plan's constraints (titles only; cheap).
pub fn fits(plan: &IsaPlan, title: &str, artist: &str, duration_s: u32) -> bool {
    let t = title.to_lowercase();
    if plan.avoid_vocals && !(t.contains("instrumental") || t.contains("lofi") || t.contains("lo-fi") || t.contains("piano") || t.contains("bgm") || t.contains("theme")) {
        let g = genre_of(title, artist, "");
        if g != "Lo-fi" && g != "Instrumental" {
            return false;
        }
    }
    if let Some(m) = plan.max_duration_s {
        if duration_s > m {
            return false;
        }
    }
    if t.contains("full album") || t.contains("jukebox") || t.contains("1 hour") || t.contains("hours") || duration_s > 900 {
        return false;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_rainy_drive() {
        let p = plan("rainy night drive, Malayalam + lo-fi");
        assert!(p.moods.contains(&"rainy".to_string()));
        assert!(p.moods.contains(&"night drive".to_string()));
        assert!(p.languages.contains(&"Malayalam".to_string()));
        assert!(p.seeds.iter().any(|s| s.starts_with("Malayalam")));
        assert!(p.energy <= 0);
    }
    #[test]
    fn focus_no_vocals() {
        let p = plan("focus, no vocals");
        assert!(p.avoid_vocals);
        assert!(p.seeds.iter().all(|s| s.contains("instrumental")));
        assert!(!fits(&p, "Blinding Lights", "The Weeknd", 200));
        assert!(fits(&p, "Deep Focus (Instrumental)", "Someone", 200));
    }
    #[test]
    fn artist_like() {
        let p = plan("something like sushin shyam");
        assert!(p.seeds[0].starts_with("sushin shyam"));
    }
    #[test]
    fn unknown_prompt_is_search() {
        let p = plan("qwerty zxcv");
        assert_eq!(p.seeds[0], "qwerty zxcv");
    }
    #[test]
    fn genres() {
        assert_eq!(genre_of("Illuminati", "Sushin Shyam, Dabzee", ""), "Malayalam film");
        assert_eq!(genre_of("x", "The Weeknd", ""), "Pop");
        assert_eq!(genre_of("lofi beats", "x", ""), "Lo-fi");
    }
}
