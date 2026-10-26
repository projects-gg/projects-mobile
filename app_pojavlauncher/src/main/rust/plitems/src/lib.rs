//! FEAT-M: the phone's door into pl-items. One JNI call draws a client jar's item atlas into a folder: `atlas.png` and
//! `index.json`, the launcher's own index format (pl_items::INDEX_FORMAT), with 3D icons drawn at the size the phone
//! shows them so their pixels stay square. Nothing panics across the boundary: any failure answers false and the app
//! keeps its stand-ins.

use std::fs;
use std::path::{Path, PathBuf};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use pl_items::{build_atlas, BuildOptions, JarSource};

/// Writes `bytes` to `path` through a temporary file in the same folder (a reader never sees half a file).
fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("part");
    fs::write(&tmp, bytes).map_err(|e| format!("{}: {e}", tmp.display()))?;
    fs::rename(&tmp, path).map_err(|e| format!("{}: {e}", path.display()))
}

fn build(jar: &str, out: &str, version: &str, block: u32) -> Result<(), String> {
    let out = PathBuf::from(out);
    fs::create_dir_all(&out).map_err(|e| format!("{}: {e}", out.display()))?;
    let mut source = JarSource::open(Path::new(jar)).map_err(|e| e.to_string())?;
    let options = BuildOptions {
        minecraft: version.to_string(),
        block_size: block.clamp(16, 64),
        image_name: "atlas.png".into(),
        atlas_width: 1024,
        // The phone draws 3D icons once at the size it shows them: no second, larger image.
        large_size: None,
        large_image_name: "atlas-48.png".into(),
    };
    let atlas = build_atlas(&mut source, &options).map_err(|e| e.to_string())?;
    let index = serde_json::to_vec(&atlas.index).map_err(|e| e.to_string())?;
    // The image first: an index is only ever next to its own image.
    write_atomic(&out.join("atlas.png"), &atlas.png)?;
    write_atomic(&out.join("index.json"), &index)
}

#[no_mangle]
pub extern "system" fn Java_gg_projects_mobile_cloud_items_PlItems_nativeBuild<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jar: JString<'local>,
    out: JString<'local>,
    version: JString<'local>,
    block: jint,
) -> jboolean {
    let text = |env: &mut JNIEnv<'local>, s: &JString<'local>| -> Option<String> { env.get_string(s).ok().map(|s| s.into()) };
    let (Some(jar), Some(out), Some(version)) = (text(&mut env, &jar), text(&mut env, &out), text(&mut env, &version)) else {
        return JNI_FALSE;
    };
    let block = u32::try_from(block).unwrap_or(32);
    match std::panic::catch_unwind(|| build(&jar, &out, &version, block)) {
        Ok(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}
