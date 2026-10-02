use acadrust::io::dwg::{DwgReadOptions, DwgReader};
use acadrust::entities::EntityType;
fn main() {
    let src = std::env::args().nth(1).unwrap();
    let bytes = std::fs::read(&src).unwrap();
    let cursor = std::io::Cursor::new(bytes.as_slice());
    let mut reader = DwgReader::from_stream_with_options(cursor, DwgReadOptions::default());
    let doc = reader.read().expect("read");
    let mut n = 0;
    for e in doc.entities() {
        match e {
            EntityType::MText(m) => {
                println!("MTEXT: {:?}", m.value); n+=1;
            }
            EntityType::Text(t) => {
                println!("TEXT: {:?}", t.value); n+=1;
            }
            EntityType::AttributeEntity(a) => {
                println!("ATTRIB: {:?}", a.value); n+=1;
            }
            _ => {}
        }
        if n > 30 { break; }
    }
}
