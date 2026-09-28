#![no_std]

use core::panic::PanicInfo;

const INPUT_CAP: usize = 128 * 1024;
static mut INPUT: [u8; INPUT_CAP] = [0; INPUT_CAP];

const RELEASE_PLAN: &[u8] = br#"{"schemaVersion":1,"requests":[{"requestId":"calendar-1","sourceRole":"CALENDAR","url":"https://example.org/calendar","method":"GET","targetToken":null}]}"#;
const RELEASE_PARSE: &[u8] = br#"{"schemaVersion":1,"observations":[{"schemaVersion":1,"extensionId":"fixture.release","providerId":"fixture","requestId":"calendar-1","sourceRole":"CALENDAR","providerSeriesKey":"series-1","rawTitle":"Fixture","sourceSeason":2,"navigationSeason":2,"installment":{"kind":"EPISODE","number":"1"},"track":"DE_SUB","claimKind":"RELEASE_LISTING","sourceDateText":null,"sourceTimeText":null,"sourceRawText":"Fixture episode 1","parsedTimestamp":null,"approximate":false,"scheduleMarker":"NONE","correctionMarker":null,"sourceUrl":"https://example.org/calendar","sourceHash":"6de0c15da0c1d5de1b834632f25bbd74d9ef0587035936acf1d9e99ed9ba370b","diagnostics":[]}],"responseReports":[{"requestId":"calendar-1","outcome":"SUCCESS","diagnostics":[]}]}"#;
const NAV_PLAN: &[u8] = br#"{"schemaVersion":1,"requests":[{"requestId":"nav-1","url":"https://example.org/nav-source"}]}"#;
const NAV_OVERVIEW: &[u8] = br#"{"schemaVersion":1,"targets":[{"schemaVersion":1,"extensionId":"fixture.release","providerId":"fixture","targetKind":"OVERVIEW","providerSeriesKey":"series-1","sourceSeason":2,"providerEpisode":null,"track":null,"url":"https://example.org/series/1","requestId":"nav-1","sourceHash":"96dfb9bbcd4f46a63e8b521bdef0b5b5357f2bd5ddf9cae543f098069b8478fb","diagnostics":[]}]}"#;
const NAV_EPISODE: &[u8] = br#"{"schemaVersion":1,"targets":[{"schemaVersion":1,"extensionId":"fixture.release","providerId":"fixture","targetKind":"EPISODE","providerSeriesKey":"series-1","sourceSeason":2,"providerEpisode":"15","track":"DE_SUB","url":"https://example.org/series/1/episode/15","requestId":"nav-1","sourceHash":"96dfb9bbcd4f46a63e8b521bdef0b5b5357f2bd5ddf9cae543f098069b8478fb","diagnostics":[]}]}"#;
const DIAGNOSTIC: &[u8] = b"fixture";

#[link(wasm_import_module = "arex_v1")]
unsafe extern "C" {
    fn diagnostic(pointer: i32, length: i32) -> i32;
}

#[panic_handler]
fn panic(_info: &PanicInfo<'_>) -> ! {
    loop {}
}

#[unsafe(no_mangle)]
pub extern "C" fn arex_alloc(length: i32) -> i32 {
    if length < 0 || length as usize > INPUT_CAP {
        return 0;
    }
    core::ptr::addr_of_mut!(INPUT).cast::<u8>() as i32
}

#[unsafe(no_mangle)]
pub extern "C" fn arex_free(_pointer: i32, _length: i32) {}

fn spin_requested(pointer: i32, length: i32) -> bool {
    if length != 1 || pointer == 0 {
        return false;
    }
    unsafe { *(pointer as *const u8) == 0x7f }
}

fn contains_input(pointer: i32, length: i32, needle: &[u8]) -> bool {
    if pointer == 0 || length <= 0 || length as usize > INPUT_CAP || needle.is_empty() {
        return false;
    }
    let input = unsafe { core::slice::from_raw_parts(pointer as *const u8, length as usize) };
    input.windows(needle.len()).any(|window| window == needle)
}

fn consume_fixture_input(pointer: i32, length: i32) {
    if pointer == 0 || length <= 0 || length as usize > INPUT_CAP {
        return;
    }
    let mut checksum = 0u32;
    for pass in 0..8u32 {
        for index in 0..length as usize {
            let value = unsafe { core::ptr::read_volatile((pointer as *const u8).add(index)) };
            checksum = checksum.rotate_left(5) ^ value as u32 ^ pass;
        }
    }
    if checksum == u32::MAX {
        unsafe { let _ = diagnostic(DIAGNOSTIC.as_ptr() as i32, DIAGNOSTIC.len() as i32); }
    }
}

fn output(bytes: &'static [u8]) -> i64 {
    unsafe {
        let _ = diagnostic(DIAGNOSTIC.as_ptr() as i32, DIAGNOSTIC.len() as i32);
    }
    (((bytes.as_ptr() as u32 as u64) << 32) | bytes.len() as u64) as i64
}

#[unsafe(no_mangle)]
pub extern "C" fn plan_requests(pointer: i32, length: i32) -> i64 {
    if spin_requested(pointer, length) {
        loop { core::hint::spin_loop(); }
    }
    output(RELEASE_PLAN)
}

#[unsafe(no_mangle)]
pub extern "C" fn parse_responses(pointer: i32, length: i32) -> i64 {
    consume_fixture_input(pointer, length);
    output(RELEASE_PARSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn plan_navigation(_pointer: i32, _length: i32) -> i64 {
    output(NAV_PLAN)
}

#[unsafe(no_mangle)]
pub extern "C" fn parse_navigation(pointer: i32, length: i32) -> i64 {
    consume_fixture_input(pointer, length);
    if contains_input(pointer, length, b"\"targetKind\":\"EPISODE\"") {
        output(NAV_EPISODE)
    } else {
        output(NAV_OVERVIEW)
    }
}
