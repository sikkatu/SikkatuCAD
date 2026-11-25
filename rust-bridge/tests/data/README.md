# Примеры файлов для SikkatuCAD / Studio

32 файла, 84,6 МБ. Источники и контрольные суммы — в `MANIFEST.json`; `python3 fetch_samples.py <папка>` скачивает всё заново и проверяет sha256.

Все файлы открываются: DWG/DXF — через acadrust 0.5.5 (чтение 0,3–35 мс), IFC — через openbim-ifc 0.42.0 `product_meshes`.

| Файл | Размер, КБ | Что это |
| --- | ---: | --- |
| `mep_dwg/SONSBEEK-BG Water+Gas+CV.dwg` | 404 | MEP DWG: вода, газ, отопление (цоколь/первый этаж). Line 6093, LwPolyline 84, Hatch 22, Insert 48, Dimension 16, MText 39, Ole2Frame 2 |
| `mep_dwg/SONSBEEK-V1 Water+Gas+CV.dwg` | 416 | MEP DWG: вода, газ, отопление (этаж V1). Line 6524, LwPolyline 105 |
| `mep_dwg/SONSBEEK-BG Rio+MV+Hwa.dwg` | 340 | MEP DWG: канализация, механическая вентиляция, ливнёвка. Line 3579, Arc 40, Dimension 16 |
| `mep_dwg/SONSBEEK-V1 Sparingen.dwg` | 188 | DWG: отверстия и проёмы под инженерные сети (Sparingen). Малый файл, Line 176 |
| `mep_ifc/Clinic_HVAC.ifc` | 26283 | IFC2X3, ОВК клиники: 3967 продуктов, 538 954 треугольника; 1 отказ меша (IfcSpace, sliver) |
| `mep_ifc/Duplex_MEP_20110907.ifc` | 17452 | IFC2X3, MEP дуплекса: 968 продуктов, 337 130 треугольников, 0 отказов |
| `mep_ifc/Duplex_Plumbing_20121113.ifc` | 30816 | IFC2X3, водопровод дуплекса: 499 продуктов, 334 682 треугольника, 0 отказов |
| `mep_ifc/Building-Hvac.ifc` | 108 | IFC4, ОВК простого здания: 5 продуктов, 1064 треугольника |
| `mep_ifc/Building-Architecture.ifc` | 138 | IFC4, архитектура того же здания: 14 продуктов; для наложения с Building-Hvac |
| `mep_ifc/Infra-Plumbing.ifc` | 342 | IFC4, водопровод: 27 продуктов, 6792 треугольника |
| `mep_ifc/Infra-Electrical.ifc` | 117 | IFC4, электрика: 4 продукта |
| `mep_ifc/building_service_element_air-terminal.ifc` | 14 | IFC4, воздухораспределитель: минимальный MEP-элемент |
| `mep_ifc/1269--Project_IfcDuctFitting.ifc` | 15 | IFC, фасонная часть воздуховода: отрицательный тест, 1 из 3 продуктов не собирается (sweep range exceeds trimmed directrix) |
| `mep_ifc/5473--pipeSegment--wrong-geometry.ifc` | 1 | IFC4, труба 17 178 треугольников; тест на производительность одного тяжёлого элемента |
| `dxf_features/3dsolids.dxf` | 157 | DXF: 4 тела 3DSOLID (ACIS), вход для проверки сохранения тел без потерь |
| `dxf_features/3dface.dxf` | 130 | DXF: 44 грани 3DFACE |
| `dxf_features/dimension_in_block.dxf` | 86 | DXF: DIMENSION внутри блока |
| `dxf_features/dimension_in_nested_blocks.dxf` | 89 | DXF: DIMENSION во вложенных блоках |
| `dxf_features/multi_insert_with_attribs.dxf` | 16 | DXF: INSERT с атрибутами |
| `dxf_features/solid.dxf` | 13 | DXF: 2 сущности SOLID (2D-заливка) |
| `dxf_features/points.dxf` | 13 | DXF: 5 POINT |
| `dxf_features/mesh-3dface.dxf` | 14 | DXF: сетка из 5 3DFACE |
| `dxf_features/dimension-linear.dxf` | 13 | DXF: линейный размер |
| `dxf_features/dimension-aligned.dxf` | 13 | DXF: выровненный размер |
| `dxf_features/ocs-faces.dxf` | 14 | DXF: SOLID и HATCH в нестандартной OCS |
| `dxf_features/block-instanced.dxf` | 16 | DXF: 8 вставок блока |
| `roundtrip/example_2000.dwg` | 568 | DWG R2000, 36 типов сущностей (Solid3D, Region, Table, MLine, Spline...) для круговой проверки |
| `roundtrip/example_2000.dxf` | 1355 | DXF той же сцены |
| `roundtrip/example_2018.dwg` | 145 | DWG R2018 той же сцены |
| `roundtrip/example_2018.dxf` | 825 | DXF R2018 той же сцены |
| `roundtrip/Point.dwg` | 27 | DWG R2018: один POINT, минимальный файл |
| `ref_ifc/AC20-FZK-Haus.ifc` | 2510 | IFC4, эталонный жилой дом (KIT FZK-Haus): 121 продукт, 107 с мешем, 22 272 треугольника, 0 отказов; главный эталон приёмки IFC |

Папки: `mep_dwg` — реальные MEP-чертежи (DWG), `mep_ifc` — IFC с инженерными системами, `dxf_features` — маленькие DXF на отдельные типы сущностей, `roundtrip` — одна сцена в DWG/DXF разных версий для проверки сохранения, `ref_ifc` — эталонный дом.
