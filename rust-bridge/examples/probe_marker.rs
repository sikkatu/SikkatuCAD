use acadrust::io::dwg::{DwgReadOptions, DwgReader};
use acadrust::entities::EntityType;
fn main() {
    let src = std::env::args().nth(1).unwrap();
    let bytes = std::fs::read(&src).unwrap();
    let cursor = std::io::Cursor::new(bytes.as_slice());
    let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::default());
    let doc = reader.read().expect("read");
    for br in doc.block_records.iter() {
        if br.name.starts_with("Стандартный Маркер") || br.name.starts_with("Линейный Размер") || br.name == "*D" {
            println!("BLOCK {:?} base={:?} n={}", br.name, br.base_point, br.entity_handles.len());
            for h in br.entity_handles.iter().take(2) {
                if let Some(e) = doc.get_entity(*h) {
                    match e {
                        EntityType::Line(l) => println!("   LINE {:?} -> {:?}", l.start, l.end),
                        EntityType::Hatch(hh) => {
                            for p in hh.paths.iter().take(1) {
                                println!("   HATCH path: {:?}", p);
                            }
                        }
                        EntityType::MText(m) => println!("   MTEXT at {:?} val={:?}", m.insertion_point, m.value),
                        EntityType::Circle(c) => println!("   CIRCLE center={:?} r={}", c.center, c.radius),
                        o => println!("   other {}", o.common().handle),
                    }
                }
            }
        }
    }
    // Hatch boundary coords in a Элемент block
    for br in doc.block_records.iter() {
        if br.name == "Элемент Разреза_2" {
            for h in br.entity_handles.iter() {
                if let Some(EntityType::Hatch(hh)) = doc.get_entity(*h) {
                    for p in hh.paths.iter().take(1) {
                        println!("Элем2 HATCH path: {:?}", p);
                    }
                    break;
                }
            }
            break;
        }
    }
}
