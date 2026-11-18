use adblock::{filters::network::NetworkFilter, lists::ParseOptions, request::{Request, RequestType}, resources::Resource, Engine, FilterSet};
use jni::{objects::{JClass, JString}, sys::{jlong, jstring}, JNIEnv};
use std::{collections::{HashMap, HashSet}, error::Error, fs, path::Path, ptr};

type Result<T, E = Box<dyn Error>> = std::result::Result<T, E>;
struct Blocker { engine: Engine, hosts: HashSet<String> }

// Kotlin can publish a candidate from another thread and serve concurrent read borrows.
const _: () = {
    fn require_shared<T: Send + Sync>() {}
    let _ = require_shared::<Blocker>;
};

#[cfg(feature = "perf-qa")]
mod perf_qa;

impl Blocker {
    fn compile(paths: &[String], private_rules: String) -> Result<Self> {
        let mut filters = FilterSet::new(false);
        let mut candidates = HashMap::new();
        let mut disabled = HashSet::new();
        let private = (!private_rules.is_empty()).then_some(Ok(private_rules));
        for source in paths.iter().map(fs::read_to_string).chain(private) {
            let text = source?;
            for line in text.lines().map(str::trim) {
                if let Some(host) = line.strip_prefix("||").and_then(|s| s.strip_suffix('^')) {
                    if host.contains('.') && host.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'.' || c == b'-') {
                        if let Ok(rule) = NetworkFilter::parse(line, false, ParseOptions::default()) {
                            candidates.insert(rule.get_id(), host.to_owned());
                        }
                    }
                } else if line.contains('$') && line.as_bytes().windows(9).any(|s| s.eq_ignore_ascii_case(b"badfilter")) {
                    if let Ok(rule) = NetworkFilter::parse(line, false, ParseOptions::default()) {
                        if rule.is_badfilter() { disabled.insert(rule.get_id()); }
                    }
                }
            }
            filters.add_filter_list(text, ParseOptions::default());
        }
        let hosts = candidates.into_iter().filter_map(|(k,h)| (!disabled.contains(&k)).then_some(h)).collect();
        Ok(Self { engine: Engine::new_with_filter_set(filters), hosts })
    }
    fn decision(&self, url: &str, source: &str, kind: &str, method: &str) -> Option<String> {
        let req = Request::new(url, source, kind, method).ok()?;
        let mut host = req.hostname.as_str();
        let hit = (req.is_http || req.is_https) && matches!(req.request_type, RequestType::Script|RequestType::Image|RequestType::Stylesheet|RequestType::Font|RequestType::Media|RequestType::Subdocument|RequestType::Xmlhttprequest|RequestType::Other) && loop {
            if self.hosts.contains(host) { break true; }
            if let Some((_, rest)) = host.split_once('.') { host = rest; } else { break false; }
        };
        // Do not return a block on a host hit: exceptions, important rules and redirect-rule still apply.
        let res = self.engine.check_network_request_subset(&req, hit, false);
        res.should_block().then(|| res.redirect.unwrap_or_default())
    }
}

fn text(env: &mut JNIEnv, s: &JString) -> Result<String> { Ok(env.get_string(s)?.into()) }

fn with_request_text<T>(
    env: &JNIEnv, url: &JString, source: &JString, kind: &JString, method: &JString,
    decide: impl FnOnce(&str, &str, &str, &str) -> T,
) -> Result<T> {
    use std::borrow::Cow;
    // SAFETY: these are the four declared java.lang.String parameters of the JNI method, not
    // objects from an untyped array. Null is checked by get_string_unchecked. JavaStr guards
    // release the VM buffers on every return; Cow still decodes modified UTF-8 when necessary.
    let (url, source, kind, method) = unsafe {
        (env.get_string_unchecked(url)?, env.get_string_unchecked(source)?,
         env.get_string_unchecked(kind)?, env.get_string_unchecked(method)?)
    };
    Ok(decide(&Cow::from(&url), &Cow::from(&source), &Cow::from(&kind), &Cow::from(&method)))
}
fn result<T: Default>(env: &mut JNIEnv, r: Result<T>) -> T {
    r.unwrap_or_else(|_| { let _ = env.throw_new("java/io/IOException", "Ad blocker data could not be processed"); T::default() })
}
// Only the Kotlin owner can call these private methods. Its read/write lock covers each borrow and destruction.
unsafe fn borrowed<'a>(h: jlong) -> Result<&'a Blocker> { (h as *const Blocker).as_ref().ok_or_else(|| "No active blocker".into()) }

#[no_mangle]
pub extern "system" fn Java_ru_spgsroot_peachy_browser_adblock_NativeFilter_open(mut env: JNIEnv, _: JClass, paths: JString, cache: JString, resources: JString, restore: jni::sys::jboolean, local: JString) -> jlong {
    let r = (|| -> Result<_> {
        let cache = text(&mut env, &cache)?; let dir = Path::new(&cache);
        let mut blocker = if restore != 0 {
            let mut engine = Engine::default(); engine.deserialize(&fs::read(dir.join("engine.bin"))?).map_err(|_| "Wrong engine format")?;
            Blocker { engine, hosts: serde_json::from_slice(&fs::read(dir.join("hosts.json"))?)? }
        } else {
            let sources: Vec<String> = serde_json::from_str(&text(&mut env, &paths)?)?;
            let private_rules = text(&mut env, &local)?;
            let public_only = private_rules.is_empty();
            let b = Blocker::compile(&sources, private_rules)?;
            if public_only {
                fs::write(dir.join("engine.bin"), b.engine.serialize())?;
                fs::write(dir.join("hosts.json"), serde_json::to_vec(&b.hosts)?)?;
            }
            b
        };
        let data: Vec<Resource> = serde_json::from_str(&text(&mut env, &resources)?)?;
        blocker.engine.use_resources(data); Ok(Box::into_raw(Box::new(blocker)) as jlong)
    })(); result(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_ru_spgsroot_peachy_browser_adblock_NativeFilter_close(_: JNIEnv, _: JClass, h: jlong) { if h != 0 { unsafe { drop(Box::from_raw(h as *mut Blocker)) } } }

#[no_mangle]
pub extern "system" fn Java_ru_spgsroot_peachy_browser_adblock_NativeFilter_decide(mut env: JNIEnv, _: JClass, h: jlong, url: JString, source: JString, kind: JString, method: JString) -> jstring {
    let r = (|| -> Result<_> {
        let blocker = unsafe { borrowed(h)? };
        let decision = with_request_text(&env, &url, &source, &kind, &method, |u, s, k, m| blocker.decision(u, s, k, m))?;
        Ok(match decision { Some(s) => env.new_string(s)?.into_raw(), None => ptr::null_mut() })
    })(); result(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_ru_spgsroot_peachy_browser_adblock_NativeFilter_page(mut env: JNIEnv, _: JClass, h: jlong, url: JString) -> jstring {
    let r = (|| -> Result<_> { let page = unsafe { borrowed(h)? }.engine.url_cosmetic_resources(&text(&mut env, &url)?); Ok(env.new_string(serde_json::to_string(&page)?)?.into_raw()) })(); result(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_ru_spgsroot_peachy_browser_adblock_NativeFilter_generic(mut env: JNIEnv, _: JClass, h: jlong, keys: JString, exceptions: JString) -> jstring {
    let r = (|| -> Result<_> { let [classes,ids]: [Vec<String>;2] = serde_json::from_str(&text(&mut env,&keys)?)?;
        let exceptions = serde_json::from_str(&text(&mut env,&exceptions)?)?;
        let selectors = unsafe { borrowed(h)? }.engine.hidden_class_id_selectors(classes,ids,&exceptions);
        Ok(env.new_string(serde_json::to_string(&selectors)?)?.into_raw()) })(); result(&mut env,r)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_shortcut_preserves_brave_priority_types_and_exceptions() {
        let file = std::env::temp_dir().join(format!("peachy-shield-{}.txt", std::process::id()));
        fs::write(&file, "||ads.test^\n@@||ads.test/ok\n||ads.test/ok/force$important\n||disabled.test^\n||disabled.test^$badfilter\n||post.test^$method=post,domain=page.test,xmlhttprequest\n||img.test^$image,third-party\n/position[0-9]+/$script\n||media.test^$redirect=noop.js\n||popup.test^$popup\npage.test##.banner\n##.generic-banner\npage.test#@#.excepted\npage.test##article:has-text(sponsor)\npage.test##+js(set-constant, fixtureMarker, true)\npage.test##+js(trusted-set-constant, secret, true)").unwrap();
        let mut blocker = Blocker::compile(&[file.to_str().unwrap().to_owned()], String::new()).unwrap();
        fs::remove_file(file).unwrap();
        let resources: Vec<Resource> = serde_json::from_str(include_str!("../../../app/src/main/assets/adblock/resources.json")).unwrap();
        blocker.engine.use_resources(resources);
        for (url, source, kind, method, expected) in [
            ("https://sub.ads.test/a", "https://page.test/", "script", "get", true),
            ("https://notads.test/a", "https://page.test/", "script", "get", false),
            ("https://ads.test/ok", "https://page.test/", "script", "get", false),
            ("https://ads.test/ok/force", "https://page.test/", "script", "get", true),
            ("https://disabled.test/a", "https://page.test/", "script", "get", false),
            ("https://post.test/a", "https://page.test/", "xmlhttprequest", "post", true),
            ("https://post.test/a", "https://page.test/", "xmlhttprequest", "get", false),
            ("https://post.test/a", "https://other.test/", "xmlhttprequest", "post", false),
            ("https://img.test/a", "https://page.test/", "image", "get", true),
            ("https://img.test/a", "https://x.img.test/", "image", "get", false),
            ("https://img.test/a", "https://page.test/", "script", "get", false),
            ("https://page.test/position34", "https://page.test/", "script", "get", true),
            // Brave 0.13.5 maps "popup" to Other; a $popup-only rule is not a WebView block.
            ("https://popup.test/", "https://page.test/", "popup", "get", false),
        ] {
            let req = Request::new(url,source,kind,method).unwrap();
            assert_eq!(blocker.engine.check_network_request(&req).should_block(), expected, "oracle {url}/{kind}/{method}");
            assert_eq!(blocker.decision(url,source,kind,method).is_some(),expected,"shortcut {url}/{kind}/{method}");
        }
        assert!(blocker.decision("https://media.test/a", "https://page.test", "script","get").unwrap().starts_with("data:application/javascript;base64,"));
        let page = blocker.engine.url_cosmetic_resources("https://page.test/path");
        assert!(page.hide_selectors.contains(".banner"));
        assert!(page.procedural_actions.iter().any(|r| r.contains("has-text")));
        assert!(page.injected_script.contains("fixtureMarker"));
        assert!(!page.injected_script.contains("\"secret\""));
        assert_eq!(blocker.engine.hidden_class_id_selectors(["generic-banner"],std::iter::empty::<&str>(),&page.exceptions), vec![".generic-banner"]);
    }
}
