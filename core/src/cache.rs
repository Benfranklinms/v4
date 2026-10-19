//! Small LRU file cache for streamed tracks (mtime = last use).

use std::fs;
use std::time::SystemTime;

pub fn get(dir: &str, key: &str) -> Option<String> {
    for ext in ["m4a", "webm"] {
        let p = format!("{dir}/{key}.{ext}");
        if let Ok(f) = fs::File::options().append(true).open(&p) {
            let _ = f.set_modified(SystemTime::now());
            return Some(p);
        }
    }
    None
}

fn entries(dir: &str) -> Vec<(String, u64, SystemTime)> {
    let Ok(rd) = fs::read_dir(dir) else { return vec![] };
    rd.filter_map(|e| e.ok())
        .filter_map(|e| {
            let m = e.metadata().ok()?;
            let p = e.path().to_string_lossy().to_string();
            if p.ends_with(".part") {
                return None;
            }
            Some((p, m.len(), m.modified().unwrap_or(SystemTime::UNIX_EPOCH)))
        })
        .collect()
}

pub fn size(dir: &str) -> u64 {
    entries(dir).iter().map(|e| e.1).sum()
}

pub fn evict(dir: &str, limit: u64) {
    let mut v = entries(dir);
    let mut total: u64 = v.iter().map(|e| e.1).sum();
    v.sort_by_key(|e| e.2);
    for (p, len, _) in v {
        if total <= limit {
            break;
        }
        if fs::remove_file(&p).is_ok() {
            total -= len;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lru() {
        let d = std::env::temp_dir().join(format!("lru{}", rand::random::<u32>()));
        fs::create_dir_all(&d).unwrap();
        let ds = d.to_string_lossy().to_string();
        for (i, k) in ["a", "b", "c"].iter().enumerate() {
            fs::write(format!("{ds}/{k}.m4a"), vec![0u8; 100]).unwrap();
            let f = fs::File::options().append(true).open(format!("{ds}/{k}.m4a")).unwrap();
            f.set_modified(SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(1000 + i as u64)).unwrap();
        }
        assert!(get(&ds, "a").is_some()); // touch a -> newest
        evict(&ds, 200);
        assert!(get(&ds, "b").is_none());
        assert!(get(&ds, "a").is_some());
        assert!(get(&ds, "c").is_some());
    }
}
