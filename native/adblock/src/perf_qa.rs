//! Compiled only into the isolated measurement APK. No instrumentation on the production hot path.
use super::*;
use std::time::Instant;
use jni::objects::JLongArray;

#[no_mangle]
pub extern "system" fn Java_ru_spgsroot_peachy_perfqa_BrowserProbe_split(mut env: JNIEnv, _: JClass, handle: jlong, url: JString, source: JString, kind: JString, method: JString, phases: JLongArray) {
    let r = (|| -> Result<()> {
        let blocker = unsafe { borrowed(handle)? };
        let start = Instant::now();
        let (converted, value, matched) = with_request_text(&env, &url, &source, &kind, &method, |u, s, k, m| {
            let converted = Instant::now();
            let value = blocker.decision(u, s, k, m);
            (converted, value, Instant::now())
        })?;
        let released = Instant::now();
        if let Some(s) = value { env.new_string(s)?; }
        let returned = Instant::now();
        let strings = converted.duration_since(start) + released.duration_since(matched);
        env.set_long_array_region(&phases, 0, &[strings.as_nanos() as i64, matched.duration_since(converted).as_nanos() as i64, returned.duration_since(released).as_nanos() as i64])?;
        Ok(())
    })();
    result(&mut env, r);
}

// Independent checked conversion oracle for modified UTF-8 on the QA APK only.
#[no_mangle]
pub extern "system" fn Java_ru_spgsroot_peachy_perfqa_BrowserProbe_checked(mut env: JNIEnv, _: JClass, handle: jlong, url: JString, source: JString, kind: JString, method: JString) -> jstring {
    let r = (|| -> Result<_> {
        let value = unsafe { borrowed(handle)? }.decision(&text(&mut env, &url)?, &text(&mut env, &source)?, &text(&mut env, &kind)?, &text(&mut env, &method)?);
        Ok(match value { Some(s) => env.new_string(s)?.into_raw(), None => ptr::null_mut() })
    })();
    result(&mut env, r)
}
