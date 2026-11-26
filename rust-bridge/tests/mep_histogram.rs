// mep_histogram (spec 5.4): sweep the real-world DWG fixtures in
// tests/data/mep_dwg (*.dwg) and tests/data/roundtrip (example_*.dwg,
// Point.dwg), printing the source-type `counts` and `unsupported` histograms
// for each. Must never panic (catch_unwind per file) and never truncate.
use acadrust::document::CadDocument;
use acadrust::io::dwg::{DwgReadOptions, DwgReader};
use cadbridge::scene_emit::build_scene;

fn read_dwg(path: &std::path::Path) -> CadDocument {
    let bytes = std::fs::read(path).unwrap();
    let mut reader =
        DwgReader::from_stream_with_options(std::io::Cursor::new(bytes), DwgReadOptions::default());
    match reader.read() {
        Ok(d) => d,
        Err(_) => {
            let bytes = std::fs::read(path).unwrap();
            let mut retry = DwgReader::from_stream_with_options(
                std::io::Cursor::new(bytes),
                DwgReadOptions::failsafe(),
            );
            retry.read().unwrap()
        }
    }
}

fn sweep_dir(root: &std::path::Path, label: &str) -> usize {
    let mut files_processed = 0usize;
    if !root.exists() {
        eprintln!("[{label}] fixtures not present — skipping");
        return 0;
    }
    for entry in std::fs::read_dir(root).unwrap() {
        let entry = entry.unwrap();
        let path = entry.path();
        let is_dwg = path.extension().map(|x| x == "dwg").unwrap_or(false);
        if !is_dwg {
            continue;
        }
        let name = path
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        let res = std::panic::catch_unwind(|| {
            let doc = read_dwg(&path);
            let scene = build_scene(&doc);
            (scene.truncated, scene.prims.len(), scene.counts, scene.unsupported)
        });
        assert!(res.is_ok(), "[{label}] panic while processing {name}");
        let (truncated, prims, counts, unsupported) = res.unwrap();
        assert!(!truncated, "[{label}] {name}: scene unexpectedly truncated");
        println!(
            "[{label}] {name}: prims={prims} counts={counts:?} unsupported={unsupported:?}"
        );
        files_processed += 1;
    }
    files_processed
}

#[test]
fn mep_histogram_sweeps_real_dwg_fixtures() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/data");
    let mep = root.join("mep_dwg");
    let roundtrip = root.join("roundtrip");
    let n1 = sweep_dir(&mep, "mep_dwg");
    let n2 = sweep_dir(&roundtrip, "roundtrip");
    // At least one DWG should have been processed when fixtures are present;
    // otherwise the `skipping` branch fired above and that's fine too.
    println!("mep_histogram done: {n1} mep_dwg files, {n2} roundtrip files");
}