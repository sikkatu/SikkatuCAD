use acadrust::io::dwg::{DwgReadOptions, DwgReader};
use acadrust::entities::EntityType;
fn main() {
    let src = std::env::args().nth(1).unwrap();
    let bytes = std::fs::read(&src).unwrap();
    let cursor = std::io::Cursor::new(bytes.as_slice());
    let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::default());
    let doc = match reader.read() {
        Ok(d) => d,
        Err(_) => {
            let cursor = std::io::Cursor::new(bytes.as_slice());
            let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::failsafe());
            reader.read().expect("read")
        }
    };
    println!("=== BlockRecords (name -> base_point, n_entities) ===");
    let mut n = 0;
    for br in doc.block_records.iter() {
        if br.name.starts_with("Элемент Разреза_") || br.name == "*D" {
            println!("  {:?}: base={:?} entities={}", br.name, br.base_point, br.entity_handles.len());
            n += 1;
            if n >= 6 { break; }
        }
    }
    println!("=== block content coords for Элемент Разреза_2 ===");
    for br in doc.block_records.iter() {
        if br.name == "Элемент Разреза_2" {
            println!("BLOCK {:?} base={:?}", br.name, br.base_point);
            for h in br.entity_handles.iter().take(5) {
                if let Some(e) = doc.get_entity(*h) {
                    match e {
                        EntityType::Line(l) => println!("   LINE start={:?} end={:?}", l.start, l.end),
                        EntityType::Hatch(hh) => println!("   HATCH layer={:?}", hh.common.layer),
                        EntityType::MText(m) => println!("   MTEXT insert={:?}", m.insertion_point),
                        other => println!("   other: {:?}", other.common().handle),
                    }
                }
            }
            break;
        }
    }
    println!("=== INSERT points (first 5) ===");
    let mut c = 0;
    for e in doc.entities() {
        if let EntityType::Insert(ins) = e {
            println!("  INSERT block={:?} pt={:?} rot={} scale=({},{})", ins.block_name, ins.insert_point, ins.rotation, ins.x_scale(), ins.y_scale());
            c += 1;
            if c >= 5 { break; }
        }
    }
}
