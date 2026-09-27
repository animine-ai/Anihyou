#![no_std]
use core::{panic::PanicInfo, ptr};
#[panic_handler]
fn panic(_: &PanicInfo) -> ! { core::arch::wasm32::unreachable() }
#[link(wasm_import_module = "arex_v1")]
unsafe extern "C" { fn diagnostic(ptr: u32, len: u32) -> i32; }
static mut INPUT: [u8; 65536] = [0; 65536];
static PLAN: &[u8] = include_bytes!("plan-output.json");
static OBS: &[u8] = include_bytes!("parse-output.json");
static BAD: &[u8] = b"{\"schemaVersion\":1,\"error\":{\"code\":\"INVALID_INPUT\"}}";
fn result(bytes: &[u8]) -> u64 { ((bytes.as_ptr() as u64) << 32) | bytes.len() as u64 }
#[no_mangle]
pub extern "C" fn arex_alloc(len: u32) -> u32 {
 if len > 65536 { return 0; }
 ptr::addr_of_mut!(INPUT) as u32
}
#[no_mangle]
pub extern "C" fn arex_free(_: u32, _: u32) {}
fn check(p: u32, n: u32, expected: &[u8]) -> bool {
 if p != ptr::addr_of_mut!(INPUT) as u32 || n as usize != expected.len() { return false; }
 unsafe { core::slice::from_raw_parts(p as *const u8,n as usize) == expected }
}
#[no_mangle]
pub extern "C" fn plan_requests(p: u32,n: u32) -> u64 {
 if !check(p,n,include_bytes!("plan-input.json")) {return result(BAD)}
 unsafe { diagnostic(b"fixture-plan".as_ptr() as u32,12); }
 result(PLAN)
}
#[no_mangle]
pub extern "C" fn parse_responses(p: u32,n: u32) -> u64 {
 if !check(p,n,include_bytes!("parse-input.json")) {return result(BAD)}
 unsafe { diagnostic(b"fixture-parse".as_ptr() as u32,13); }
 result(OBS)
}
// Deliberately hostile test exports. These are not production ABI exports.
#[no_mangle]
pub extern "C" fn test_spin() {loop { core::hint::spin_loop(); }}
#[no_mangle]
pub extern "C" fn test_grow() -> i32 {core::arch::wasm32::memory_grow::<0>(100) as i32}
#[no_mangle]
pub extern "C" fn test_oversize() -> u64 {((PLAN.as_ptr() as u64)<<32) | 1048577}
#[no_mangle]
pub extern "C" fn test_oob() -> u64 {(0xfffffff0u64<<32)|64}
#[no_mangle]
pub extern "C" fn test_crash() {core::arch::wasm32::unreachable()}
#[no_mangle]
pub extern "C" fn test_host_abuse() {loop {unsafe {diagnostic(b"x".as_ptr() as u32,1);}}}
