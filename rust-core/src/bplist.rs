//! Apple 二进制 plist（bplist00）编解码（cardeck `BinaryPlist.kt` 的 Rust 移植）。
//!
//! 协议依据与两条硬约束（改此文件前必读）：
//! 1. **根对象必须落在字节 8**：本编码器先递归编码子对象、最后才追加根容器，
//!    默认根在末尾；但 CarPlay 210.81 一类的老车机**不查 trailer 的 topObject，
//!    直接读偏移 8**（catplay 上游 9ad3a7e「fix recent encoder regression with
//!    CarPlay 210.81 that hardcodes expected offset of the root object」）。
//!    Apple 自家编码器本来就是根在前，这里用 `make_root_first` 按同一布局重排
//!    以匹配真实 iPhone。
//! 2. **负整数固定写 8 字节**：Apple CoreFoundation 的 bplist00 将 1/2/4 字节整数
//!    按无符号读取，仅 8 字节按有符号读取；-1 写成单字节 FF 会变成 255。
//!    正数保留现有保守宽度（例如端口 35449 仍写 4 字节），避免无关的兼容性变更。
//!    依据：swift-corelibs-foundation/Sources/CoreFoundation/CFBinaryPList.c 整数解析分支。
//!
//! 解析端覆盖 /info 与 RTSP 报文用到的类型：null/bool/int/real/date/data/
//! ASCII/UTF-16BE/UTF-8 字符串/UID/UUID/array/set/dict；集合与字符串的扩展长度
//! （0x…F + int 对象）按**无符号**解读（DiPlay 的 182B data 曾被有符号读成 -74）。

/// 二进制 plist 值模型。`Dict` 保留**插入顺序**的键值对（与编码顺序一致）。
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int(i64),
    Real(f64),
    String(String),
    Data(Vec<u8>),
    Uid(u64),
    Array(Vec<Value>),
    /// 有序键值对（键通常为 String，保留原始顺序）。
    Dict(Vec<(Value, Value)>),
}

impl Value {
    /// 便捷构造字符串键字典（键顺序 = 传入顺序，编码时原样保留）。
    pub fn dict(pairs: Vec<(&str, Value)>) -> Value {
        Value::Dict(
            pairs
                .into_iter()
                .map(|(key, value)| (Value::String(key.to_string()), value))
                .collect(),
        )
    }

    pub fn int(v: i64) -> Value {
        Value::Int(v)
    }

    pub fn str(v: &str) -> Value {
        Value::String(v.to_string())
    }

    pub fn bool(v: bool) -> Value {
        Value::Bool(v)
    }

    pub fn real(v: f64) -> Value {
        Value::Real(v)
    }

    pub fn array(items: Vec<Value>) -> Value {
        Value::Array(items)
    }

    pub fn as_int(&self) -> Option<i64> {
        if let Value::Int(v) = self {
            Some(*v)
        } else {
            None
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        if let Value::Bool(v) = self {
            Some(*v)
        } else {
            None
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        if let Value::String(v) = self {
            Some(v.as_str())
        } else {
            None
        }
    }

    pub fn as_bytes(&self) -> Option<&[u8]> {
        if let Value::Data(v) = self {
            Some(v.as_slice())
        } else {
            None
        }
    }

    pub fn as_array(&self) -> Option<&Vec<Value>> {
        if let Value::Array(v) = self {
            Some(v)
        } else {
            None
        }
    }

    pub fn as_dict(&self) -> Option<&Vec<(Value, Value)>> {
        if let Value::Dict(v) = self {
            Some(v)
        } else {
            None
        }
    }

    /// 仅 Dict 有效：按键（String 键）取值，保持引用返回。
    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Dict(pairs) => pairs
                .iter()
                .find(|(k, _)| matches!(k, Value::String(s) if s.as_str() == key))
                .map(|(_, v)| v),
            _ => None,
        }
    }
}

const MAGIC: &[u8; 8] = b"bplist00";
const MAX_OBJECTS: u64 = 1 << 20;
const MAX_PARSE_DEPTH: usize = 128;
const MAX_DECODED_BYTES: usize = 32 * 1024 * 1024;

// Object count in the trailer does not bound an expanded tree: references can be
// cyclic or can repeatedly share a large subtree. Bound visits and copied payloads.
struct ParseBudget { remaining_nodes: usize, remaining_bytes: usize }
impl ParseBudget {
    fn visit(&mut self, depth: usize) -> Result<(), String> {
        if depth > MAX_PARSE_DEPTH { return Err("plist nesting too deep or cyclic".into()); }
        self.remaining_nodes = self.remaining_nodes.checked_sub(1).ok_or("plist expanded object budget exceeded")?;
        Ok(())
    }
    fn copy(&mut self, bytes: usize) -> Result<(), String> {
        self.remaining_bytes = self.remaining_bytes.checked_sub(bytes).ok_or("plist decoded byte budget exceeded")?;
        Ok(())
    }
}

/// bplist00 编码。**根对象会被重排到偏移 8**（老车机不读 trailer topObject）；
/// map 键顺序 = `Value::Dict` 的插入顺序；负数固定 8 字节，正数沿用保守宽度。
pub fn encode(root: &Value) -> Vec<u8> {
    // 预遍历：引用宽度必须按最终对象总数（含容器自身）确定
    let total = count_objects(root);
    let mut objects: Vec<Vec<u8>> = Vec::new();
    let root_ref = encode_object(root, &mut objects, total);

    let mut offset: u64 = 8;
    let mut offsets: Vec<u64> = Vec::with_capacity(objects.len());
    for object in &objects {
        offsets.push(offset);
        offset += object.len() as u64;
    }
    // 偏移表宽度按「整体长度」取，保证每个偏移都放得下
    let offset_int_size = int_bytes(offset);
    let ref_size = int_bytes(total as u64);
    let table_offset = offset;
    let table_size = (objects.len() as u64) * offset_int_size as u64;

    let mut out: Vec<u8> =
        Vec::with_capacity((offset + table_size + 32) as usize);
    out.extend_from_slice(MAGIC);
    for object in &objects {
        out.extend_from_slice(object);
    }
    for entry in &offsets {
        write_uint_vec(&mut out, *entry, offset_int_size);
    }
    // trailer: unused(6) offsetIntSize(1) refSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
    out.extend_from_slice(&[0u8; 6]);
    out.push(offset_int_size as u8);
    out.push(ref_size as u8);
    write_uint_vec(&mut out, objects.len() as u64, 8);
    write_uint_vec(&mut out, root_ref as u64, 8);
    write_uint_vec(&mut out, table_offset, 8);
    make_root_first(out)
}

/// 把根对象整体搬到偏移 8 并修正偏移表（catplay `make_root_first` 同算法）。
/// 输入必须是本文件刚生成的合法布局（根是最后一个对象）；任何校验不过都原样返回。
fn make_root_first(data: Vec<u8>) -> Vec<u8> {
    if data.len() < 41 || &data[..8] != MAGIC {
        return data;
    }
    let trailer = data.len() - 32;
    let width = data[trailer + 6] as usize;
    if width != 1 && width != 2 && width != 4 && width != 8 {
        return data;
    }
    let Ok(count) = read_uint(&data, trailer + 8, 8) else {
        return data;
    };
    let Ok(root) = read_uint(&data, trailer + 16, 8) else {
        return data;
    };
    let Ok(table) = read_uint(&data, trailer + 24, 8) else {
        return data;
    };
    let (count, root, table) = (count as usize, root as usize, table as usize);
    let Some(table_end) = count
        .checked_mul(width)
        .and_then(|span| table.checked_add(span))
    else {
        return data;
    };
    if root >= count || table < 9 || table_end > trailer {
        return data;
    }
    let entry = |index: usize| -> Option<usize> { table.checked_add(index.checked_mul(width)?) };

    let Some(root_entry) = entry(root) else {
        return data;
    };
    let Ok(root_start) = read_uint(&data, root_entry, width) else {
        return data;
    };
    let root_start = root_start as usize;
    if root_start < 8 || root_start >= table {
        return data;
    }
    if root_start == 8 {
        return data;
    }
    // 根之后（到偏移表之前）属于根对象的字节块；其余对象都排在它前面
    let mut root_end = table;
    for index in 0..count {
        let Some(at) = entry(index) else {
            return data;
        };
        let Ok(offset) = read_uint(&data, at, width) else {
            return data;
        };
        let offset = offset as usize;
        if offset < 8 || offset >= table {
            return data;
        }
        if offset > root_start && offset < root_end {
            root_end = offset;
        }
    }
    if root_end <= root_start {
        return data;
    }
    let root_len = root_end - root_start;
    let tail_len = root_start - 8; // 根块之前属于其它对象的字节数
    let mut out = data.clone();
    // data[8..root_end) 右旋 root_len：等价于把根块搬到最前、其余对象顺延
    out[8..8 + root_len].copy_from_slice(&data[root_start..root_start + root_len]);
    out[8 + root_len..8 + root_len + tail_len].copy_from_slice(&data[8..8 + tail_len]);
    for index in 0..count {
        let Some(at) = entry(index) else {
            return data;
        };
        let Ok(offset) = read_uint(&data, at, width) else {
            return data;
        };
        let offset = offset as usize;
        let adjusted = if index == root {
            8
        } else if offset < root_start {
            offset + root_len
        } else {
            offset
        };
        write_uint_into(&mut out, adjusted as u64, width, at);
    }
    out
}

/// 预遍历统计对象总数（容器自身也算一个对象）。
fn count_objects(value: &Value) -> usize {
    match value {
        Value::Array(items) => 1 + items.iter().map(count_objects).sum::<usize>(),
        Value::Dict(pairs) => {
            1 + pairs
                .iter()
                .map(|(k, v)| count_objects(k) + count_objects(v))
                .sum::<usize>()
        }
        _ => 1,
    }
}

/// 递归编码单个对象：**先编码子对象、再追加容器自身**（因此根总是最后一个，
/// 由 `make_root_first` 搬到偏移 8）。返回该对象在 objects 表中的下标。
fn encode_object(value: &Value, objects: &mut Vec<Vec<u8>>, total: usize) -> usize {
    let bytes: Vec<u8> = match value {
        Value::Null => vec![0x00],
        Value::Bool(flag) => vec![if *flag { 0x09 } else { 0x08 }],
        Value::Int(number) => encode_int(*number),
        Value::Real(number) => {
            let mut out = vec![0x23];
            out.extend_from_slice(&number.to_bits().to_be_bytes());
            out
        }
        Value::Data(data) => with_length(0x40, data, data.len()),
        Value::Uid(uid) => encode_uid(*uid),
        Value::String(text) => encode_string(text),
        Value::Array(items) => {
            let refs: Vec<usize> = items
                .iter()
                .map(|item| encode_object(item, objects, total))
                .collect();
            let payload = refs_to_bytes(&refs, total);
            with_length(0xA0, &payload, refs.len())
        }
        Value::Dict(pairs) => {
            let mut refs: Vec<usize> = pairs
                .iter()
                .map(|(key, _)| encode_object(key, objects, total))
                .collect();
            refs.extend(
                pairs
                    .iter()
                    .map(|(_, value)| encode_object(value, objects, total)),
            );
            let payload = refs_to_bytes(&refs, total);
            with_length(0xD0, &payload, pairs.len())
        }
    };
    objects.push(bytes);
    objects.len() - 1
}

/// 纯 ASCII 用 0x5X（ASCII），否则用 0x6X（UTF-16BE，长度按 UTF-16 码元计）。
fn encode_string(value: &str) -> Vec<u8> {
    if value.is_ascii() {
        with_length(0x50, value.as_bytes(), value.len())
    } else {
        let units: Vec<u16> = value.encode_utf16().collect();
        let unit_count = units.len();
        let mut be = Vec::with_capacity(unit_count * 2);
        for unit in units {
            be.extend_from_slice(&unit.to_be_bytes());
        }
        with_length(0x60, &be, unit_count)
    }
}

/// UID：0x8X，X+1 字节大端（取能容纳的最小 1/2/4/8 字节宽度）。
fn encode_uid(uid: u64) -> Vec<u8> {
    let size = if uid <= u8::MAX as u64 {
        1
    } else if uid <= u16::MAX as u64 {
        2
    } else if uid <= u32::MAX as u64 {
        4
    } else {
        8
    };
    let mut out = vec![0x80 | (size as u8 - 1)];
    write_uint_vec(&mut out, uid, size);
    out
}

/// bplist00 短整数无符号，负数必须以 8 字节表示；正数保留已有宽度。
fn encode_int(value: i64) -> Vec<u8> {
    let size: usize = if value < 0 {
        8
    } else if value <= i8::MAX as i64 {
        1
    } else if (i16::MIN as i64..=i16::MAX as i64).contains(&value) {
        2
    } else if (i32::MIN as i64..=i32::MAX as i64).contains(&value) {
        4
    } else {
        8
    };
    let exponent: u8 = match size {
        1 => 0,
        2 => 1,
        4 => 2,
        _ => 3,
    };
    let mut out = Vec::with_capacity(1 + size);
    out.push(0x10 | exponent);
    let bits = value.to_be_bytes();
    out.extend_from_slice(&bits[8 - size..]);
    out
}

/// 长度头：count < 15 直接并进 marker；否则 marker 低 4 位取 0xF，
/// 长度跟一个标准 int 对象（**无符号语义**，读取端同款处理）。
fn with_length(marker: u8, payload: &[u8], count: usize) -> Vec<u8> {
    if count < 15 {
        let mut out = Vec::with_capacity(1 + payload.len());
        out.push(marker | (count as u8));
        out.extend_from_slice(payload);
        out
    } else {
        let length_bytes = encode_int(count as i64);
        let mut out = Vec::with_capacity(1 + length_bytes.len() + payload.len());
        out.push(marker | 0x0F);
        out.extend_from_slice(&length_bytes);
        out.extend_from_slice(payload);
        out
    }
}

/// 对象引用表序列化：按「对象总数」决定的宽度写大端下标。
fn refs_to_bytes(refs: &[usize], object_count: usize) -> Vec<u8> {
    let width = int_bytes(object_count as u64);
    let mut out: Vec<u8> = Vec::with_capacity(refs.len() * width);
    for reference in refs {
        write_uint_vec(&mut out, *reference as u64, width);
    }
    out
}

/// 无符号大端写出到 Vec。
fn write_uint_vec(out: &mut Vec<u8>, value: u64, size: usize) {
    let bits = value.to_be_bytes();
    out.extend_from_slice(&bits[8 - size..]);
}

/// 无符号大端原地写入（make_root_first 修正偏移表用）。
fn write_uint_into(target: &mut [u8], value: u64, size: usize, at: usize) {
    for index in 0..size {
        target[at + index] = (value >> (8 * (size - 1 - index))) as u8;
    }
}

/// 放下 value 所需的最小大端字节宽度（1/2/4/8）。
fn int_bytes(value: u64) -> usize {
    if value < 0x100 {
        1
    } else if value < 0x1_0000 {
        2
    } else if value < 0x1_0000_0000 {
        4
    } else {
        8
    }
}

/// 大端读取 size 字节为 u64（size ∈ 0..=8；越界报错，语义同 Kotlin readUint）。
fn read_uint(bytes: &[u8], offset: usize, size: usize) -> Result<u64, String> {
    if size > 8 {
        return Err(format!("读取宽度非法（size={} offset={}）", size, offset));
    }
    let end = offset
        .checked_add(size)
        .ok_or_else(|| format!("读取越界（offset={} size={}）", offset, size))?;
    if end > bytes.len() {
        return Err(format!(
            "读取越界（offset={} size={} body={}B）——trailer 与报文实际长度不一致时会出现",
            offset,
            size,
            bytes.len()
        ));
    }
    let mut value: u64 = 0;
    for index in 0..size {
        value = (value << 8) | bytes[offset + index] as u64;
    }
    Ok(value)
}

fn add_usize(a: usize, b: usize) -> Result<usize, String> {
    a.checked_add(b)
        .ok_or_else(|| format!("偏移计算溢出（{}+{}）", a, b))
}

fn mul_usize(a: usize, b: usize) -> Result<usize, String> {
    a.checked_mul(b)
        .ok_or_else(|| format!("长度计算溢出（{}×{}）", a, b))
}

/// 安全切片：起止越界时报错（替代 Kotlin copyOfRange 抛异常的行为）。
fn slice_of<'a>(bytes: &'a [u8], start: usize, count: usize, what: &str) -> Result<&'a [u8], String> {
    let end = add_usize(start, count)
        .map_err(|_| format!("{}长度溢出（start={} count={}）", what, start, count))?;
    if end > bytes.len() {
        return Err(format!(
            "{}越界（start={} count={} body={}B）",
            what,
            start,
            count,
            bytes.len()
        ));
    }
    Ok(&bytes[start..end])
}

fn hex_bytes(bytes: &[u8]) -> String {
    let mut out = String::new();
    for (index, byte) in bytes.iter().enumerate() {
        if index > 0 {
            out.push(' ');
        }
        out.push_str(&format!("{:02x}", byte));
    }
    out
}

/// 解析 bplist00。trailer 从**末尾倒数 32 字节**定位，故不接受尾部填充；
/// 报文带尾随字节时由调用方先裁剪。错误信息含 trailer 摘要便于排查。
pub fn parse(bytes: &[u8]) -> Result<Value, String> {
    if bytes.len() < 40 {
        return Err(format!("plist 太短（{} 字节）", bytes.len()));
    }
    if &bytes[..8] != MAGIC {
        return Err(format!(
            "不是 bplist00（首 8 字节 {}）",
            hex_bytes(&bytes[..8])
        ));
    }

    // 尾部 32 字节 trailer：unused(6) offsetIntSize(1) objectRefSize(1)
    //                      numObjects(8) topObject(8) offsetTableOffset(8)
    let offset_int_size = bytes[bytes.len() - 26] as usize;
    let object_ref_size = bytes[bytes.len() - 25] as usize;
    let num_objects = read_uint(bytes, bytes.len() - 24, 8)?;
    let top_object = read_uint(bytes, bytes.len() - 16, 8)?;
    let offset_table_offset = read_uint(bytes, bytes.len() - 8, 8)?;
    let trailer = format!(
        "trailer{{offsetIntSize={} objectRefSize={} numObjects={} topObject={} offsetTableOffset={} body={}B}}",
        offset_int_size, object_ref_size, num_objects, top_object, offset_table_offset, bytes.len()
    );
    if !(1..=8).contains(&offset_int_size) || !(1..=8).contains(&object_ref_size) {
        return Err(format!("trailer 字段不合法 {}", trailer));
    }
    if num_objects == 0 || num_objects > MAX_OBJECTS {
        return Err(format!("对象数非法 {}", trailer));
    }
    if top_object >= num_objects {
        return Err(format!("根对象下标越界 {}", trailer));
    }
    let table_end = offset_table_offset
        .checked_add(num_objects * offset_int_size as u64)
        .ok_or_else(|| format!("偏移表越界 {}", trailer))?;
    if offset_table_offset < 9 || table_end > (bytes.len() - 32) as u64 {
        return Err(format!("偏移表越界 {}", trailer));
    }

    let count = num_objects as usize;
    let table = offset_table_offset as usize;
    let mut offsets: Vec<u64> = Vec::with_capacity(count);
    for index in 0..count {
        let offset = read_uint(bytes, table + index * offset_int_size, offset_int_size)?;
        if offset < 8 || offset >= offset_table_offset { return Err("plist object outside object table".into()); }
        offsets.push(offset);
    }
    let mut budget = ParseBudget { remaining_nodes: MAX_OBJECTS as usize, remaining_bytes: MAX_DECODED_BYTES };
    parse_object(&bytes[..table], offsets[top_object as usize] as usize, object_ref_size, &offsets, 0, &mut budget)
}

/// 递归解析单个对象（下标经 offsets 表换算成绝对偏移）。
fn parse_object(
    bytes: &[u8],
    offset: usize,
    ref_size: usize,
    offsets: &[u64],
    depth: usize,
    budget: &mut ParseBudget,
) -> Result<Value, String> {
    budget.visit(depth)?;
    let marker = *bytes
        .get(offset)
        .ok_or_else(|| format!("对象标记越界（offset={} body={}B）", offset, bytes.len()))?;
    let kind = marker >> 4;
    let info = marker & 0x0F;
    match kind {
        0x0 => Ok(match info {
            0x8 => Value::Bool(false),
            0x9 => Value::Bool(true),
            _ => Value::Null,
        }),

        // 整数一律按**无符号**读入再落进 i64：接收端会把位图/微秒/采样率写进
        // 2 字节 int，值超过 32767 时按有符号读会变成负数（见文件头注释）
        0x1 => Ok(Value::Int(read_uint(bytes, offset + 1, 1usize << info)? as i64)),

        0x2 => match info {
            0x2 => Ok(Value::Real(
                f32::from_bits(read_uint(bytes, offset + 1, 4)? as u32) as f64
            )),
            0x3 => Ok(Value::Real(f64::from_bits(read_uint(bytes, offset + 1, 8)?))),
            _ => Ok(Value::Null),
        },

        // 日期：CFAbsoluteTime（2001-01-01 起算秒数），按 f64 原样返回
        0x3 => Ok(Value::Real(f64::from_bits(read_uint(bytes, offset + 1, 8)?))),

        0x4 => {
            let (count, start) = element_count(bytes, offset, info)?;
            budget.copy(count)?;
            Ok(Value::Data(slice_of(bytes, start, count, "data")?.to_vec()))
        }

        0x5 => {
            let (count, start) = element_count(bytes, offset, info)?;
            budget.copy(mul_usize(count, 3)?)?;
            let raw = slice_of(bytes, start, count, "ASCII 字符串")?;
            Ok(Value::String(String::from_utf8_lossy(raw).into_owned()))
        }

        0x6 => {
            let (count, start) = element_count(bytes, offset, info)?;
            budget.copy(mul_usize(count, 5)?)?;
            let raw = slice_of(bytes, start, mul_usize(count, 2)?, "UTF-16 字符串")?;
            let units: Vec<u16> = raw
                .chunks_exact(2)
                .map(|pair| u16::from_be_bytes([pair[0], pair[1]]))
                .collect();
            let text =
                String::from_utf16(&units).map_err(|error| format!("UTF-16 字符串非法：{}", error))?;
            Ok(Value::String(text))
        }

        0x7 => {
            let (count, start) = element_count(bytes, offset, info)?;
            budget.copy(mul_usize(count, 3)?)?;
            let raw = slice_of(bytes, start, count, "UTF-8 字符串")?;
            Ok(Value::String(String::from_utf8_lossy(raw).into_owned()))
        }

        // UID：长度由 info 决定（info+1 字节），**不是** refSize——按 refSize 读会
        // 在 refSize>1 时多吃掉后续字节，把整条 plist 的对象指针读歪
        0x8 => Ok(Value::Uid(read_uint(bytes, offset + 1, info as usize + 1)?)),

        0xB => {
            // UUID：info+1 字节定长，越界时截到缓冲区末尾（同 Kotlin minOf 兜底）
            let size = info as usize + 1;
            let start = offset + 1;
            let end = start.saturating_add(size).min(bytes.len());
            Ok(Value::Data(bytes[start..end].to_vec()))
        }

        0xA | 0xC => {
            // array / set
            let (count, start) = element_count(bytes, offset, info)?;
            slice_of(bytes, start, mul_usize(count, ref_size)?, "array references")?;
            if count > budget.remaining_nodes { return Err("plist expanded object budget exceeded".into()); }
            let mut items: Vec<Value> = Vec::with_capacity(count.min(MAX_OBJECTS as usize));
            for index in 0..count {
                let reference =
                    read_uint(bytes, add_usize(start, mul_usize(index, ref_size)?)?, ref_size)?
                        as usize;
                let object_offset = *offsets
                    .get(reference)
                    .ok_or_else(|| format!("对象引用越界（ref={}）", reference))?
                    as usize;
                items.push(parse_object(bytes, object_offset, ref_size, offsets, depth + 1, budget)?);
            }
            Ok(Value::Array(items))
        }

        0xD => {
            // dict：前 count 个引用是键、后 count 个是值，保持出现顺序
            let (count, start) = element_count(bytes, offset, info)?;
            slice_of(bytes, start, mul_usize(mul_usize(count, 2)?, ref_size)?, "dict references")?;
            if mul_usize(count, 2)? > budget.remaining_nodes { return Err("plist expanded object budget exceeded".into()); }
            let mut pairs: Vec<(Value, Value)> = Vec::with_capacity(count.min(MAX_OBJECTS as usize));
            for index in 0..count {
                let key_at = add_usize(start, mul_usize(index, ref_size)?)?;
                let value_at = add_usize(start, mul_usize(add_usize(count, index)?, ref_size)?)?;
                let key_reference = read_uint(bytes, key_at, ref_size)? as usize;
                let value_reference = read_uint(bytes, value_at, ref_size)? as usize;
                let key_offset = *offsets
                    .get(key_reference)
                    .ok_or_else(|| format!("键引用越界（ref={}）", key_reference))?
                    as usize;
                let value_offset = *offsets
                    .get(value_reference)
                    .ok_or_else(|| format!("值引用越界（ref={}）", value_reference))?
                    as usize;
                let key = parse_object(bytes, key_offset, ref_size, offsets, depth + 1, budget)?;
                let value = parse_object(bytes, value_offset, ref_size, offsets, depth + 1, budget)?;
                pairs.push((key, value));
            }
            Ok(Value::Dict(pairs))
        }

        _ => Ok(Value::Null),
    }
}

/// 集合/字符串/数据长度：info < 0xF 时长度就是 info；否则长度存在随后的
/// int 对象里（按**无符号**读，≥128 的扩展长度曾被有符号读成负数）。
/// 返回 (count, 数据起始偏移)。
fn element_count(bytes: &[u8], offset: usize, info: u8) -> Result<(usize, usize), String> {
    if info != 0x0F {
        return Ok((info as usize, offset + 1));
    }
    let marker = *bytes
        .get(offset + 1)
        .ok_or_else(|| format!("长度字节缺失（offset={} body={}B）", offset, bytes.len()))?;
    if marker >> 4 != 0x1 {
        return Err(format!(
            "期望长度 int 对象，得到 0x{:02x}（offset={}）",
            marker, offset
        ));
    }
    let int_size = 1usize << (marker & 0x0F);
    let count = read_uint(bytes, offset + 2, int_size)?;
    if count > MAX_OBJECTS {
        return Err(format!("长度字段非法（count={} offset={}）", count, offset));
    }
    Ok((count as usize, offset + 2 + int_size))
}


#[cfg(test)]
mod tests {
    use super::*;

    fn graph_fixture(objects: &[Vec<u8>]) -> Vec<u8> {
        let mut bytes = MAGIC.to_vec();
        let mut offsets = Vec::new();
        for object in objects {
            offsets.push(bytes.len());
            bytes.extend_from_slice(object);
        }
        let table = bytes.len();
        for offset in offsets { write_uint_vec(&mut bytes, offset as u64, 4); }
        bytes.extend_from_slice(&[0; 6]);
        bytes.extend_from_slice(&[4, 1]);
        write_uint_vec(&mut bytes, objects.len() as u64, 8);
        write_uint_vec(&mut bytes, 0, 8);
        write_uint_vec(&mut bytes, table as u64, 8);
        bytes
    }

    #[test]
    fn cyclic_and_excessively_deep_reference_graphs_are_errors() {
        let cyclic = graph_fixture(&[vec![0xa1, 0]]);
        assert!(parse(&cyclic).unwrap_err().contains("nesting"));
        let mut objects: Vec<Vec<u8>> = (0..MAX_PARSE_DEPTH + 2)
            .map(|i| vec![0xa1, (i + 1) as u8]).collect();
        objects.push(vec![0x09]);
        assert!(parse(&graph_fixture(&objects)).unwrap_err().contains("nesting"));
    }

    #[test]
    fn shared_references_are_valid_but_expansion_has_a_budget() {
        let bytes = graph_fixture(&[vec![0xa2, 1, 1], vec![0x44, 1, 2, 3, 4]]);
        assert_eq!(parse(&bytes).unwrap(), Value::Array(vec![Value::Data(vec![1,2,3,4]); 2]));
        let mut budget = ParseBudget { remaining_nodes: 10, remaining_bytes: 7 };
        assert!(parse_object(&bytes, 8, 1, &[8, 11], 0, &mut budget)
            .unwrap_err().contains("byte budget"));
        let mut budget = ParseBudget { remaining_nodes: 2, remaining_bytes: 100 };
        assert!(parse_object(&bytes, 8, 1, &[8, 11], 0, &mut budget)
            .unwrap_err().contains("object budget"));
    }

    #[test]
    fn object_offsets_cannot_reference_header_or_offset_table() {
        for bad_offset in [0u64, 9] {
            let mut bytes = graph_fixture(&[vec![0x09]]);
            bytes[9..13].copy_from_slice(&(bad_offset as u32).to_be_bytes());
            assert!(parse(&bytes).unwrap_err().contains("outside object table"));
        }
    }


    #[test]
    fn negative_integers_match_independent_python_plist_fixtures() {
        let fixtures: serde_json::Value = serde_json::from_str(include_str!("../testdata/bplist-negative-integers.json")).unwrap();
        for case in fixtures["cases"].as_array().unwrap() {
            let value = case["value"].as_i64().unwrap();
            let hex = case["hex"].as_str().unwrap();
            let expected: Vec<u8> = (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i+2], 16).unwrap()).collect();
            let encoded = encode(&Value::Int(value));
            assert_eq!(encoded, expected, "independent bplist bytes for {value}");
            assert_eq!(parse(&encoded).unwrap(), Value::Int(value));
            assert_eq!(encoded[8], 0x13);
        }
    }

    #[test]
    fn positive_integer_wire_widths_and_boundaries_remain_unchanged() {
        for (value, width) in [
            (0, 1), (127, 1), (128, 2), (255, 2), (32767, 2),
            (32768, 4), (35449, 4), (65535, 4), (i32::MAX as i64, 4),
            (i32::MAX as i64 + 1, 8), (i64::MAX, 8),
        ] {
            let encoded = encode_int(value);
            assert_eq!(encoded.len(), width + 1, "width for {value}");
            assert_eq!(&encoded[1..], &value.to_be_bytes()[8-width..]);
            assert_eq!(parse(&encode(&Value::Int(value))).unwrap(), Value::Int(value));
        }
    }

    #[test]
    fn short_incoming_integers_keep_unsigned_semantics() {
        // Root-first scalar fixtures emitted by independent parsers may use unsigned short ints.
        for (value, width) in [(255i64, 1usize), (65535, 2), (u32::MAX as i64, 4)] {
            let mut encoded = encode(&Value::Int(0));
            let root_size = (1u8 << (encoded[8] & 0x0f)) as usize;
            let old_table = encoded.len() - 32 - 1;
            let exponent = match width { 1 => 0, 2 => 1, _ => 2 };
            let mut object = vec![0x10 | exponent];
            object.extend_from_slice(&value.to_be_bytes()[8-width..]);
            encoded.splice(8..9+root_size, object);
            let table = (old_table + width - root_size) as u64;
            let n = encoded.len(); encoded[n-8..].copy_from_slice(&table.to_be_bytes());
            assert_eq!(parse(&encoded).unwrap(), Value::Int(value));
        }
    }

    #[test]
    fn nested_negative_status_and_extended_lengths_survive_encoding() {
        let input = Value::dict(vec![
            ("status", Value::Int(-6722)),
            ("items", Value::Array(vec![Value::Int(-1); 182])),
            ("data", Value::Data(vec![0xab; 182])),
        ]);
        let encoded = encode(&input);
        assert_eq!(encoded[8] >> 4, 0xd, "root stays first");
        assert_eq!(parse(&encoded).unwrap(), input);
    }
}
