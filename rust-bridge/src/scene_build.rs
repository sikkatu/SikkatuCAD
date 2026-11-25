//! Scene export from an acadrust CadDocument (spec 5.1).
//! Part 1: text de-formatting and GUID extraction helpers.
use acadrust::entities::EntityType;
use acadrust::xdata::XDataValue;

/// Strip MTEXT formatting codes. Ported 1:1 from the legacy Kotlin
/// `DocumentParser.plainText` (spec 5.1: "Снятие MTEXT-форматирования
/// перенести ... «как есть», сохранив те же правила (\S, \P, %%d/c/p, ~)").
pub fn plain_text(raw: &str) -> String {
    let mut t = raw.to_string();
    // Stacking \S top^bottom; -> top/bottom (empty bottom -> just top,
    // with the degree-sign exception on Cyrillic/Latin "o")
    t = replace_regex_simple(&t, r"\\S([^;{}]*);", |caps: &str| -> String {
        let mut inner = caps.to_string();
        let _ = &mut inner;
        String::new() // placeholder, replaced below by custom scanner
    });

    // The Kotlin version used regex captures; we implement the same
    // semantics with a tiny scanner to avoid pulling the `regex` crate.
    let t = stack_stacking(t);
    let t = t.replace("\\P", "\n");
    let t = strip_commands_with_arg(&t);
    let t = strip_a_prefix(&t);
    let t = t
        .chars()
        .map(|c| if c == '\\' || c == '{' || c == '}' { '\u{0}' } else { c })
        .collect::<String>();
    let t = t.replace('\u{0}', "");
    // Single-char commands without ';'
    let t = strip_single_commands(&t);
    let t = t
        .replace("%%d", "°")
        .replace("%%D", "°")
        .replace("%%c", "Ø")
        .replace("%%C", "Ø")
        .replace("%%p", "±")
        .replace("%%P", "±");
    let t = t.replace('~', " ");
    t.trim().to_string()
}

// `replace_regex_simple` is kept only until the scanner port lands; it is a
// no-op below to keep the function body compiling the same as the Kotlin
// call stack.
fn replace_regex_simple(input: &str, _pat: &str, _map: impl Fn(&str) -> String) -> String {
    input.to_string()
}

fn stack_stacking(input: String) -> String {
    let mut out = String::new();
    let chars: Vec<char> = input.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '\\' && i + 1 < chars.len() && chars[i + 1] == 'S' {
            // find closing ';' at same nesting (Kotlin regex stops at ;{ } characters)
            let mut j = i + 2;
            let mut body = String::new();
            let mut found = false;
            while j < chars.len() {
                let c = chars[j];
                if c == ';' {
                    found = true;
                    break;
                }
                if c == '{' || c == '}' {
                    break;
                }
                body.push(c);
                j += 1;
            }
            if found {
                let mut parts = body.splitn(2, '|');
                // original splits on '^'; same algorithm:
                let caret_split: Vec<&str> = body.splitn(2, '^').collect();
                let top = caret_split.first().unwrap_or(&"").trim();
                let bottom = caret_split.get(1).map(|s| s.trim()).unwrap_or("").to_string();
                let _ = parts.next();
                if bottom.is_empty() {
                    let lower = top.to_lowercase();
                    if lower == "о" || lower == "o" {
                        out.push('°');
                    } else {
                        out.push_str(top);
                    }
                } else {
                    out.push_str(top);
                    out.push('/');
                    out.push_str(&bottom);
                }
                i = j + 1;
                continue;
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

fn strip_commands_with_arg(input: &str) -> String {
    // Kotlin: Regex("""\\[A-Za-z][^;\\{}]*;""") -> ""
    let chars: Vec<char> = input.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '\\' && i + 1 < chars.len() && chars[i + 1].is_ascii_alphabetic() {
            let mut j = i + 2;
            let mut stopped = false;
            while j < chars.len() && chars[j] != ';' {
                if chars[j] == '\\' || chars[j] == '{' || chars[j] == '}' {
                    stopped = true;
                    break;
                }
                j += 1;
            }
            if !stopped && j < chars.len() {
                i = j + 1;
                continue;
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

fn strip_a_prefix(input: &str) -> String {
    // Kotlin: Regex("""\\A[0-9]*""") -> ""
    let bytes = input;
    let mut out = String::new();
    let mut chars = bytes.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            if let Some('A') = chars.peek().copied() {
                chars.next();
                while let Some(&d) = chars.peek() {
                    if d.is_ascii_digit() {
                        chars.next();
                    } else {
                        break;
                    }
                }
                continue;
            }
        }
        out.push(c);
    }
    out
}

fn strip_single_commands(input: &str) -> String {
    // Kotlin: Regex("""\\[A-Za-z]""") -> ""
    let mut out = String::new();
    let mut chars = input.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            if let Some(&d) = chars.peek() {
                if d.is_ascii_alphabetic() {
                    chars.next();
                    continue;
                }
            }
        }
        out.push(c);
    }
    out
}

/// Extract `guid:<GUID>` from SKT XDATA (group 1000, first string record).
pub fn guid_from_xdata(e: &EntityType) -> Option<String> {
    let record = e.common().extended_data.get_record("SKT")?;
    for value in &record.values {
        if let XDataValue::String(s) = value {
            if let Some(rest) = s.strip_prefix("guid:") {
                let guid = rest.trim().to_string();
                if !guid.is_empty() {
                    return Some(guid);
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_text_rules_match_legacy_kotlin() {
        // \S top^bottom; consumes everything up to ';' in the same group
        // and emits "top/bottom"; remaining leading "Top" stays put.
        assert_eq!(plain_text("Top\\SBottom^Top;."), "TopBottom/Top.");
        // \P -> newline (literal, not regex); a lone "\P" trims to empty
        // because the legacy Kotlin code also does .trim() at the end.
        assert_eq!(plain_text("\\P"), "");
        assert_eq!(plain_text("a\\Pb"), "a\nb");
        // \-command up to ';' including its argument is dropped wholesale
        assert_eq!(plain_text("\\fArial|b0|i0;Hi"), "Hi");
        assert_eq!(plain_text("\\W1.2;W"), "W");
        // \A followed by digits (often braceless like \A1{...})
        assert_eq!(plain_text("\\A1{xyz}"), "xyz");
        // AutoCAD specials
        assert_eq!(plain_text("100%%d"), "100°");
        assert_eq!(plain_text("%%p0.5"), "±0.5");
        // ~ becomes a space
        assert_eq!(plain_text("x ~ y"), "x   y");
        // MTEXT paragraph braces removed
        assert_eq!(plain_text("{xyz}"), "xyz");
    }
}