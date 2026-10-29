// Okulum Benim Uygulamasının koruma modülü.

use jni::objects::JClass;
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;
use std::time::{SystemTime, UNIX_EPOCH};

#[no_mangle]
pub extern "system" fn Java_com_okulumbenim_app_SecurityCore_isTimeTampered(
    _env: JNIEnv,
    _class: JClass,
    last_trusted_unix_time: jlong,
    elapsed_monotonic_seconds: jlong,
) -> jboolean {
    let secure_time = (last_trusted_unix_time + elapsed_monotonic_seconds) as u64;

    let system_time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    // Öğrenci Ayarlardan saati değiştirmesin diye koruma:
    let is_tampered = system_time < secure_time || system_time > (secure_time + 60);

    if is_tampered {
        1 // True
    } else {
        0 // False
    }
}