use acadrust::io::dwg::{DwgReadOptions, DwgReader};
use acadrust::entities::EntityType;
fn main() {
    let src = std::env::args().nth(1).unwrap();
    let bytes = std::fs::read(&src).unwrap();
    let cursor = std::io::Cursor::new(bytes.as_slice());
    let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::default());
    let doc = reader.read().expect("read");
    for br in doc.block_records.iter() {
        if br.name == "*D" {
            println!("*D base_point={:?} n_entities={}", br.base_point, br.entity_handles.len());
            for h in br.entity_handles.iter().take(3) {
                if let Some(e) = doc.get_entity(*h) {
                    match e {
                        EntityType::Line(l) => println!("   LINE {:?} -> {:?}", l.start, l.end),
                        EntityType::MText(m) => println!("   MTEXT {:?} = {:?}", m.insertion_point, m.value),
                        EntityType::AttributeDefinition(a) => println!("   ATTDEF {:?} = {:?}", a.insertion_point, a.default_value),
                        o => println!("   other {:?}", o.common().handle),
                    }
                }
            }
        }
    }
    // DIMENSION entity details
    for e in doc.entities() {
        if let EntityType::Dimension(d) = e {
            println!("DIMENSION block_name={:?} insertion_point={:?} definition_point={:?} text_middle={:?}", d.block_name, d.insertion_point, d.definition_point, d.text_middle_point);
            break;
        }
    }
}
