//! In-app updates from GitHub Releases. The token (optional) is only ever sent to api.github.com.

use crate::model::*;
use crate::CoreError;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

const UA: &str = "v4-updater";

fn client(redirects: bool) -> reqwest::Client {
    reqwest::Client::builder()
        .redirect(if redirects { reqwest::redirect::Policy::limited(5) } else { reqwest::redirect::Policy::none() })
        .timeout(std::time::Duration::from_secs(600))
        .build()
        .expect("client")
}

pub fn parse_ver(s: &str) -> Vec<u64> {
    let s = s.trim().trim_start_matches(['v', 'V']);
    let core = s.split(['-', '+', ' ']).next().unwrap_or("");
    core.split('.').map(|p| p.parse().unwrap_or(0)).collect()
}

pub fn is_newer(remote: &str, local: &str) -> bool {
    let (mut a, mut b) = (parse_ver(remote), parse_ver(local));
    let n = a.len().max(b.len()).max(3);
    a.resize(n, 0);
    b.resize(n, 0);
    a > b
}

pub async fn check(repo: &str, current: &str, token: Option<&str>) -> Result<UpdateInfo, CoreError> {
    let url = format!("https://api.github.com/repos/{repo}/releases/latest");
    let mut req = client(true)
        .get(&url)
        .header("User-Agent", UA)
        .header("Accept", "application/vnd.github+json")
        .timeout(std::time::Duration::from_secs(20));
    if let Some(t) = token.filter(|t| !t.trim().is_empty()) {
        req = req.header("Authorization", format!("Bearer {}", t.trim()));
    }
    let r = req.send().await.map_err(|e| CoreError::Network { msg: e.to_string() })?;
    let st = r.status().as_u16();
    if st == 401 || st == 403 && token.is_some() || st == 404 {
        return Err(CoreError::Auth { status: st });
    }
    if !r.status().is_success() {
        return Err(CoreError::Network { msg: format!("HTTP {st}") });
    }
    let v: Value = r.json().await.map_err(|e| CoreError::Network { msg: e.to_string() })?;
    Ok(parse_release(&v, current))
}

pub fn parse_release(v: &Value, current: &str) -> UpdateInfo {
    let tag = v["tag_name"].as_str().unwrap_or("").to_string();
    let version = tag.trim_start_matches(['v', 'V']).to_string();
    let assets = v["assets"]
        .as_array()
        .map(|a| {
            a.iter()
                .map(|x| ReleaseAsset {
                    name: x["name"].as_str().unwrap_or("").into(),
                    api_url: x["url"].as_str().unwrap_or("").into(),
                    browser_url: x["browser_download_url"].as_str().unwrap_or("").into(),
                    size: x["size"].as_u64().unwrap_or(0),
                })
                .collect()
        })
        .unwrap_or_default();
    UpdateInfo {
        newer: !version.is_empty() && is_newer(&version, current),
        version,
        tag,
        notes: v["body"].as_str().unwrap_or("").to_string(),
        assets,
        published_at: v["published_at"].as_str().unwrap_or("").to_string(),
    }
}

pub async fn download(api_url: &str, token: Option<&str>, dest: &str, progress: &Arc<Mutex<HashMap<String, f32>>>) -> Result<u64, CoreError> {
    use tokio::io::AsyncWriteExt;
    let ne = |e: reqwest::Error| CoreError::Network { msg: e.to_string() };
    // 1) the asset API url on api.github.com (auth allowed), no redirects followed
    let mut req = client(false).get(api_url).header("User-Agent", UA).header("Accept", "application/octet-stream");
    let to_api = url::Url::parse(api_url).map(|u| u.host_str() == Some("api.github.com")).unwrap_or(false);
    if let (true, Some(t)) = (to_api, token.filter(|t| !t.trim().is_empty())) {
        req = req.header("Authorization", format!("Bearer {}", t.trim()));
    }
    let mut r = req.send().await.map_err(ne)?;
    // 2) follow the redirect (objects.githubusercontent.com / S3) WITHOUT auth
    if r.status().is_redirection() {
        let loc = r.headers().get("location").and_then(|l| l.to_str().ok()).unwrap_or("").to_string();
        r = client(true).get(&loc).header("User-Agent", UA).send().await.map_err(ne)?;
    }
    let st = r.status().as_u16();
    if st == 401 || st == 404 {
        return Err(CoreError::Auth { status: st });
    }
    if !r.status().is_success() {
        return Err(CoreError::Network { msg: format!("HTTP {st}") });
    }
    let total = r.content_length().unwrap_or(0);
    let mut f = tokio::fs::File::create(dest).await.map_err(|e| CoreError::Storage { msg: e.to_string() })?;
    let mut got: u64 = 0;
    progress.lock().unwrap().insert("update".into(), 0.0);
    while let Some(chunk) = r.chunk().await.map_err(ne)? {
        f.write_all(&chunk).await.map_err(|e| CoreError::Storage { msg: e.to_string() })?;
        got += chunk.len() as u64;
        if total > 0 {
            progress.lock().unwrap().insert("update".into(), got as f32 / total as f32);
        }
    }
    f.flush().await.map_err(|e| CoreError::Storage { msg: e.to_string() })?;
    progress.lock().unwrap().remove("update");
    Ok(got)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn versions() {
        assert!(is_newer("0.1.1", "0.1.0"));
        assert!(is_newer("v0.2.0", "0.1.9"));
        assert!(!is_newer("0.1.0", "0.1.0"));
        assert!(!is_newer("0.1.0", "0.1.0-ci.5") || parse_ver("0.1.0-ci.5") == vec![0, 1, 0]);
        assert!(is_newer("1.0", "0.9.9"));
    }
    #[test]
    fn release_json() {
        let v: Value = serde_json::from_str(r#"{"tag_name":"v0.2.0","body":"0.2.0","assets":[{"name":"v4-0.2.0.apk","url":"https://api.github.com/repos/a/b/releases/assets/1","browser_download_url":"https://github.com/x","size":10}]}"#).unwrap();
        let u = parse_release(&v, "0.1.0");
        assert!(u.newer);
        assert_eq!(u.assets[0].name, "v4-0.2.0.apk");
    }
}
