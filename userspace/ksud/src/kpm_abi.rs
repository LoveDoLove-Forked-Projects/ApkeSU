use std::io;

pub fn parse_info_output(buffer: &[u8], operation_result: i32) -> io::Result<String> {
    let reported = usize::try_from(operation_result).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "native KPM info length is invalid",
        )
    })?;
    if reported > buffer.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "native KPM info is larger than its buffer",
        ));
    }
    let output = if reported == 0 {
        buffer
    } else {
        &buffer[..reported]
    };
    let end = output.iter().position(|byte| *byte == 0).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "native KPM info is not NUL terminated",
        )
    })?;

    // SukiSU reports zero after a successful copy. Positive lengths remain
    // accepted for images produced by earlier ApkeSU builds.
    Ok(String::from_utf8_lossy(&output[..end]).trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::parse_info_output;

    #[test]
    fn accepts_sukisu_zero_result() {
        let mut buffer = [0u8; 64];
        buffer[..18].copy_from_slice(b"name=hello\nvalue=1");

        assert_eq!(
            parse_info_output(&buffer, 0).unwrap(),
            "name=hello\nvalue=1"
        );
    }

    #[test]
    fn accepts_legacy_positive_result() {
        let mut buffer = [0u8; 32];
        buffer[..5].copy_from_slice(b"hello");

        assert_eq!(parse_info_output(&buffer, 6).unwrap(), "hello");
    }

    #[test]
    fn rejects_invalid_output() {
        assert!(parse_info_output(&[b'x'; 8], 0).is_err());
        assert!(parse_info_output(&[0; 8], 9).is_err());
        assert!(parse_info_output(&[0; 8], -5).is_err());
    }
}
