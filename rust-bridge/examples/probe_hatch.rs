use acadrust::io::dwg::{DwgReadOptions, DwgReader};
use acadrust::entities::EntityType;
fn main() {
    let src = std::env::args().nth(1).unwrap();
    let bytes = std::fs::read(&src).unwrap();
    let cursor = std::io::Cursor::new(bytes.as_slice());
    let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::default());
    let doc = reader.read().expect("read");
    for br in doc.block_records.iter() {
        if br.name == "Элемент Разреза_2" {
            println!("BLOCK {:?} base={:?}", br.name, br.base_point);
            for h in br.entity_handles.iter() {
                if let Some(e) = doc.get_entity(*h) {
                    match e {
                        EntityType::Hatch(hh) => {
                            println!("  HATCH layer={:?} boundary_paths={}", hh.common.layer, hh.boundary_paths.len());
                            for p in hh.boundary_paths.iter().take(2) {
                                println!("    path: {:?}", p);
                            }
                        }
                        EntityType::Line(l) => println!("  LINE {:?} -> {:?}", l.start, l.end),
                        o => println!("  {:?}", o.common().handle),
                    }
                }
            }
        }
    }
}
