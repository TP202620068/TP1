use anydoc::{ConvertError, Format};
use jni::{
    Env, EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    jni_sig, jni_str,
    objects::{JLongArray, JObject, JString, JValue, Reference},
    sys::jlong,
};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    path::Path,
};

struct Conversion {
    code: &'static str,
    detail: String,
    format: Option<Format>,
    pages: Vec<jlong>,
    page_count: jlong,
}

impl Conversion {
    fn failure(error: ConvertError, format: Option<Format>) -> Self {
        let (code, pages, page_count) = match &error {
            ConvertError::Unsupported(_) => ("unsupported", vec![], 0),
            ConvertError::NeedsOcr { pages, page_count } => (
                "needsOcr", pages.iter().map(|&page| jlong::from(page)).collect(), jlong::from(*page_count),
            ),
            ConvertError::Malformed { .. } => ("malformed", vec![], 0),
            ConvertError::Encrypted => ("encrypted", vec![], 0),
            ConvertError::ResourceLimit { .. } => ("resourceLimit", vec![], 0),
            ConvertError::MissingPart { .. } => ("missingPart", vec![], 0),
            ConvertError::Io(_) => ("io", vec![], 0),
            _ => ("unknown", vec![], 0),
        };
        Self { code, detail: error.to_string().chars().take(2048).collect(), format, pages, page_count }
    }
}

fn detect(bytes: &[u8], hint: Option<&str>) -> Option<Format> {
    Format::from_bytes(bytes).or_else(|| hint.and_then(Format::from_extension))
}

fn convert(input: &Path, hint: Option<&str>, output: &Path, input_limit: u64, output_limit: u64) -> Conversion {
    let mut format = None;
    let result = (|| -> Result<(), ConvertError> {
        let mut bytes = Vec::new();
        File::open(input)?.take(input_limit.saturating_add(1)).read_to_end(&mut bytes)?;
        if bytes.len() as u64 > input_limit {
            return Err(ConvertError::ResourceLimit { limit: "input_bytes", detail: "Input exceeds app limit".into() });
        }
        format = detect(&bytes, hint);
        let markdown = anydoc::to_markdown_bytes(&bytes, format)?;
        if markdown.len() as u64 > output_limit {
            return Err(ConvertError::ResourceLimit { limit: "markdown_bytes", detail: "Markdown exceeds app limit".into() });
        }
        let mut file = OpenOptions::new().write(true).create_new(true).open(output)?;
        file.write_all(markdown.as_bytes())?;
        file.sync_all()?;
        Ok(())
    })();
    match result {
        Ok(()) => Conversion { code: "ok", detail: String::new(), format, pages: vec![], page_count: 0 },
        Err(error) => Conversion::failure(error, format),
    }
}

fn to_java<'local>(env: &mut Env<'local>, result: Conversion) -> jni::errors::Result<JObject<'local>> {
    let code = JObject::from(JString::from_str(env, result.code)?);
    let detail = JObject::from(JString::from_str(env, result.detail)?);
    let format = match result.format {
        Some(format) => JObject::from(JString::from_str(env, format!("{format:?}").to_ascii_lowercase())?),
        None => JObject::null(),
    };
    let pages = JLongArray::new(env, result.pages.len())?;
    pages.set_region(env, 0, &result.pages)?;
    let pages = JObject::from(pages);
    env.new_object(
        jni_str!("com/example/tp1/extraction/NativeConversion"),
        &jni_sig!("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[JJ)V"),
        &[JValue::Object(&code), JValue::Object(&detail), JValue::Object(&format),
          JValue::Object(&pages), JValue::Long(result.page_count)],
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_tp1_extraction_AnyDocNative_convertNative<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _receiver: JObject<'caller>,
    input: JString<'caller>,
    hint: JString<'caller>,
    output: JString<'caller>,
    input_limit: jlong,
    output_limit: jlong,
) -> JObject<'caller> {
    // with_env catches unwinding panics; the policy preserves pending Java exceptions.
    unowned_env.with_env(|env| -> jni::errors::Result<JObject<'caller>> {
        let input = input.try_to_string(env)?;
        let output = output.try_to_string(env)?;
        let hint = if hint.is_null() { None } else { Some(hint.try_to_string(env)?) };
        if input_limit <= 0 || output_limit <= 0 {
            env.throw_new(jni_str!("java/lang/IllegalArgumentException"), jni_str!("Conversion limits must be positive"))?;
            return Ok(JObject::null());
        }
        let conversion = convert(Path::new(&input), hint.as_deref(), Path::new(&output), input_limit as u64, output_limit as u64);
        to_java(env, conversion)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixtures_detect_content_preserve_unicode_and_report_failure() {
        let rtf = include_bytes!("../../../../androidTest/assets/extraction/sample.rtf");
        assert_eq!(detect(rtf, Some("csv")), Some(Format::Rtf));
        assert!(anydoc::to_markdown_bytes(rtf, detect(rtf, None)).unwrap().contains("TP1 office fixture"));
        let csv = include_bytes!("../../../../androidTest/assets/extraction/sample.csv");
        assert_eq!(detect(csv, None), None);
        let markdown = anydoc::to_markdown_bytes(csv, detect(csv, Some("csv"))).unwrap();
        assert!(markdown.contains("Español") && markdown.contains("日本語"));
        assert!(matches!(anydoc::to_markdown_bytes(b"unknown", None), Err(ConvertError::Unsupported(_))));
        assert_eq!(Conversion::failure(ConvertError::NeedsOcr { pages: vec![1, 3], page_count: 4 }, Some(Format::Pdf)).pages, vec![1, 3]);

        let directory = std::env::temp_dir().join(format!("tp1-anydoc-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&directory).unwrap();
        let input = directory.join("input");
        let output = directory.join("document.md");
        std::fs::write(&input, csv).unwrap();
        assert_eq!(convert(&input, Some("csv"), &output, csv.len() as u64 - 1, 4096).code, "resourceLimit");
        assert_eq!(convert(&input, Some("csv"), &output, csv.len() as u64, 1).code, "resourceLimit");
        assert!(!output.exists());
        assert_eq!(convert(&input, Some("csv"), &output, csv.len() as u64, 4096).code, "ok");
        let original = std::fs::read(&output).unwrap();
        assert_eq!(convert(&input, Some("csv"), &output, csv.len() as u64, 4096).code, "io");
        assert_eq!(std::fs::read(&output).unwrap(), original);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
