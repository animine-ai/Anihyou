//! EP01-only Wasmtime/JNI experiment. No WASI, files, network or ambient imports.
use anyhow::{anyhow, bail, ensure, Context, Result};
use jni::{objects::{JByteArray, JClass, JObject}, sys::jstring, JNIEnv};
use serde_json::{json, Value};
use std::{fmt, panic::{catch_unwind, AssertUnwindSafe}, ptr, thread, time::{Duration, Instant}};
use wasmtime::{Caller, Config, Engine, Instance, Linker, Memory, Module, Store,
    StoreLimits, StoreLimitsBuilder, Trap};

const INPUT_LIMIT: usize = 65_536;
const OUTPUT_LIMIT: usize = 1_048_576;
const MEMORY_LIMIT: usize = 4 * 1_048_576;
const NORMAL_FUEL: u64 = 2_000_000;

#[derive(Debug)]
struct Budget(&'static str);
impl fmt::Display for Budget {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str(self.0) }
}
impl std::error::Error for Budget {}

struct State { limits: StoreLimits, diagnostic_calls: u32, diagnostic_bytes: usize }
struct Host { store: Store<State>, instance: Instance, memory: Memory }

fn engine(metered: bool) -> Result<Engine> {
    let mut config = Config::new();
    config.consume_fuel(metered).epoch_interruption(metered);
    // Explicit checks avoid installing a competing signal handler in Android ART.
    // The StoreLimits cap is independent of these virtual-memory reservations.
    config.signals_based_traps(false).memory_reservation(MEMORY_LIMIT as u64)
        .memory_guard_size(0).memory_reservation_for_growth(0);
    Engine::new(&config)
}

fn checked_range(pointer: u32, length: u32, memory_size: usize,
                 cap: usize, cap_error: &'static str, bounds_error: &'static str)
    -> Result<std::ops::Range<usize>> {
    let length = length as usize;
    if length > cap { return Err(Budget(cap_error).into()); }
    let start = pointer as usize;
    let end = start.checked_add(length).ok_or(Budget(bounds_error))?;
    if end > memory_size { return Err(Budget(bounds_error).into()); }
    Ok(start..end)
}

fn state() -> State {
    State {
        limits: StoreLimitsBuilder::new().memory_size(MEMORY_LIMIT)
            .instances(1).memories(1).tables(1).table_elements(1024).build(),
        diagnostic_calls: 0, diagnostic_bytes: 0,
    }
}

impl Host {
    fn new(engine: &Engine, module: &Module, fuel: u64) -> Result<Self> {
        let mut store = Store::new(engine, state());
        store.limiter(|state| &mut state.limits);
        store.set_fuel(fuel)?;
        store.set_epoch_deadline(1 << 60);
        store.epoch_deadline_trap();
        let mut linker = Linker::new(engine);
        linker.func_wrap("arex_v1", "diagnostic",
            |mut caller: Caller<'_, State>, pointer: i32, length: i32| -> Result<i32> {
                caller.data_mut().diagnostic_calls += 1;
                if caller.data().diagnostic_calls > 32 { return Err(Budget("HOST_CALL_LIMIT").into()); }
                let memory = caller.get_export("memory").and_then(|v| v.into_memory())
                    .ok_or_else(|| anyhow!("guest memory missing"))?;
                let range = checked_range(pointer as u32, length as u32,
                    memory.data_size(&caller), 256, "HOST_BOUNDS", "HOST_BOUNDS")?;
                // Copy only after validating unsigned wasm offsets; no raw guest pointers.
                let mut bytes = [0u8; 256];
                memory.read(&caller, range.start, &mut bytes[..range.len()])?;
                caller.data_mut().diagnostic_bytes += range.len();
                Ok(0)
            })?;
        let instance = linker.instantiate(&mut store, module)?;
        let memory = instance.get_memory(&mut store, "memory")
            .ok_or_else(|| anyhow!("guest memory missing"))?;
        Ok(Self { store, instance, memory })
    }

    fn read(&self, packed: i64) -> Result<Vec<u8>> {
        let bits = packed as u64;
        let range = checked_range((bits >> 32) as u32, bits as u32,
            self.memory.data_size(&self.store), OUTPUT_LIMIT, "OUTPUT_LIMIT", "OUTPUT_BOUNDS")?;
        let mut bytes = vec![0; range.len()];
        self.memory.read(&self.store, range.start, &mut bytes)?;
        Ok(bytes)
    }

    fn call(&mut self, operation: &str, input: &[u8]) -> Result<Vec<u8>> {
        if input.len() > INPUT_LIMIT { return Err(Budget("INPUT_LIMIT").into()); }
        let alloc = self.instance.get_typed_func::<i32, i32>(&mut self.store, "arex_alloc")?;
        let pointer = alloc.call(&mut self.store, input.len() as i32)?;
        let range = checked_range(pointer as u32, input.len() as u32,
            self.memory.data_size(&self.store), INPUT_LIMIT, "INPUT_LIMIT", "INPUT_BOUNDS")?;
        self.memory.write(&mut self.store, range.start, input)?;
        let function = self.instance.get_typed_func::<(i32, i32), i64>(&mut self.store, operation)?;
        let packed = function.call(&mut self.store, (pointer, input.len() as i32))?;
        let output = self.read(packed)?;
        let free = self.instance.get_typed_func::<(i32, i32), ()>(&mut self.store, "arex_free")?;
        free.call(&mut self.store, (pointer, input.len() as i32))?;
        Ok(output)
    }

    fn void(&mut self, name: &str) -> Result<()> {
        self.instance.get_typed_func::<(), ()>(&mut self.store, name)?.call(&mut self.store, ())
    }
    fn packed(&mut self, name: &str) -> Result<i64> {
        self.instance.get_typed_func::<(), i64>(&mut self.store, name)?.call(&mut self.store, ())
    }
}

fn expect_budget<T>(result: Result<T>, name: &'static str) -> Result<()> {
    let error = result.err().ok_or_else(|| anyhow!("expected {name}"))?;
    ensure!(error.downcast_ref::<Budget>().map(|e| e.0) == Some(name), "expected {name}: {error:#}");
    Ok(())
}
fn expect_trap<T>(result: Result<T>, trap: Trap) -> Result<()> {
    let error = result.err().ok_or_else(|| anyhow!("expected trap {trap:?}"))?;
    ensure!(error.downcast_ref::<Trap>() == Some(&trap), "expected {trap:?}: {error:#}");
    Ok(())
}

// Each interrupt owns its Engine: an epoch bump cannot cancel an unrelated Store.
fn epoch_test(wasm: &[u8], delay: Duration) -> Result<u128> {
    let engine = engine(true)?;
    let module = Module::new(&engine, wasm)?;
    let mut host = Host::new(&engine, &module, u64::MAX)?;
    host.store.set_epoch_deadline(1);
    let interrupted_engine = engine.clone();
    let start = Instant::now();
    let interrupter = thread::spawn(move || {
        thread::sleep(delay);
        interrupted_engine.increment_epoch();
    });
    let result = host.void("test_spin");
    interrupter.join().map_err(|_| anyhow!("epoch interrupter panicked"))?;
    expect_trap(result, Trap::Interrupt)?;
    ensure!(start.elapsed() < Duration::from_secs(2), "epoch interrupt latency");
    Ok(start.elapsed().as_millis())
}

fn suite(wasm: &[u8], plan_input: &[u8], plan_output: &[u8],
         parse_input: &[u8], parse_output: &[u8]) -> Result<Value> {
    let start = Instant::now();
    let cold = Instant::now();
    let engine = engine(true)?;
    let module = Module::new(&engine, wasm)?;
    let compile_micros = cold.elapsed().as_micros();
    let cold = Instant::now();
    let mut host = Host::new(&engine, &module, NORMAL_FUEL)?;
    let cold_micros = cold.elapsed().as_micros();
    let mut checks = Vec::<String>::new();
    let mut diagnostic_calls = 0;
    let mut diagnostic_bytes = 0;
    for (name, function, input, expected) in [
        ("plan", "plan_requests", plan_input, plan_output),
        ("parse", "parse_responses", parse_input, parse_output),
    ] {
        if name == "parse" { host = Host::new(&engine, &module, NORMAL_FUEL)?; }
        let actual = host.call(function, input)?;
        ensure!(actual == expected, "{name} exact fixture output");
        let _: Value = serde_json::from_slice(&actual).context("valid output JSON")?;
        diagnostic_calls += host.store.data().diagnostic_calls;
        diagnostic_bytes += host.store.data().diagnostic_bytes;
        host = Host::new(&engine, &module, NORMAL_FUEL)?;
        ensure!(host.call(function, input)? == expected, "{name} deterministic repeat");
        diagnostic_calls += host.store.data().diagnostic_calls;
        diagnostic_bytes += host.store.data().diagnostic_bytes;
        host = Host::new(&engine, &module, NORMAL_FUEL)?;
        let input_text = std::str::from_utf8(input)?;
        ensure!(input_text.contains("\"schemaVersion\":1"), "schema test input prerequisite");
        let wrong = input_text.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        let rejected: Value = serde_json::from_slice(&host.call(function, wrong.as_bytes())?)?;
        ensure!(rejected["error"]["code"] == "INVALID_INPUT", "{name} schema rejected");
        checks.push(format!("{name} JSON roundtrip, deterministic repeat, incompatible schema rejected"));
    }
    ensure!(diagnostic_calls == 4 && diagnostic_bytes == 50, "bounded diagnostic import");
    checks.push("bounded host import".into());
    checks.push("fresh Store and instance for each operation".into());
    let pages = host.memory.size(&host.store);
    let grow = host.instance.get_typed_func::<(), i32>(&mut host.store, "test_grow")?
        .call(&mut host.store, ())?;
    ensure!(grow == -1 && pages == 4 && host.memory.size(&host.store) == pages, "guest memory.grow denied");
    // Prove the actual 4 MiB boundary as well as the fixture's rejected 100-page growth.
    host.memory.grow(&mut host.store, 64 - pages)?;
    ensure!(host.memory.data_size(&host.store) == MEMORY_LIMIT, "memory reaches cap");
    ensure!(host.memory.grow(&mut host.store, 1).is_err(), "memory above cap denied");
    checks.push("4 MiB StoreLimits memory cap; guest grow fails without changing memory".into());
    let oversized = host.packed("test_oversize")?;
    expect_budget(host.read(oversized), "OUTPUT_LIMIT")?;
    checks.push("OUTPUT_LIMIT".into());
    let out_of_bounds = host.packed("test_oob")?;
    expect_budget(host.read(out_of_bounds), "OUTPUT_BOUNDS")?;
    checks.push("OUTPUT_BOUNDS".into());
    expect_budget(host.call("plan_requests", &vec![0; INPUT_LIMIT + 1]), "INPUT_LIMIT")?;
    checks.push("INPUT_LIMIT".into());
    let mut fuel = Host::new(&engine, &module, 10_000)?;
    expect_trap(fuel.void("test_spin"), Trap::OutOfFuel)?;
    checks.push("FUEL".into());
    let deadline_millis = epoch_test(wasm, Duration::from_millis(150))?;
    checks.push("DEADLINE via epoch interrupt".into());
    let cancellation_millis = epoch_test(wasm, Duration::from_millis(20))?;
    checks.push("CANCELLED via independent host thread epoch interrupt".into());
    let mut abuse = Host::new(&engine, &module, NORMAL_FUEL)?;
    expect_budget(abuse.void("test_host_abuse"), "HOST_CALL_LIMIT")?;
    ensure!(abuse.store.data().diagnostic_calls == 33, "32 diagnostic calls allowed");
    checks.push("HOST_CALL_LIMIT".into());
    let mut crash = Host::new(&engine, &module, NORMAL_FUEL)?;
    expect_trap(crash.void("test_crash"), Trap::UnreachableCodeReached)?;
    drop(crash);
    checks.push("guest crash isolated".into());
    ensure!(Host::new(&engine, &module, NORMAL_FUEL)?.call("plan_requests", plan_input)? == plan_output,
        "recovery after trap");
    checks.push("fresh invocation after crash".into());
    let warm = Instant::now();
    for _ in 0..10 {
        let mut fresh = Host::new(&engine, &module, NORMAL_FUEL)?;
        ensure!(fresh.call("plan_requests", plan_input)? == plan_output, "fresh instance output");
        ensure!(fresh.store.data().diagnostic_calls == 1, "fresh host state");
    }
    checks.push("10 repeated fresh instances".into());
    Ok(json!({"checks": checks, "runtime": "Wasmtime 37.0.0 Cranelift JNI (no WASI)",
        "moduleCompileMicros": compile_micros, "coldInstantiationMicros": cold_micros,
        "tenFreshCallsMicros": warm.elapsed().as_micros(), "elapsedMillis": start.elapsed().as_millis(),
        "deadlineMillis": deadline_millis, "cancellationMillis": cancellation_millis,
        "memoryLimitBytes": MEMORY_LIMIT, "diagnosticCalls": diagnostic_calls,
        "diagnosticBytes": diagnostic_bytes, "nativeTarget": std::env::consts::ARCH}))
}

fn input(env: &mut JNIEnv<'_>, array: &JByteArray<'_>) -> Result<Vec<u8>> {
    ensure!(env.get_array_length(array)? as usize <= OUTPUT_LIMIT, "JNI asset bound");
    Ok(env.convert_byte_array(array)?)
}
fn throw(env: &mut JNIEnv<'_>, message: String) {
    // Preserve any original VM exception (e.g. OOM) instead of overwriting it.
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/RuntimeException", message);
    }
}

#[no_mangle]
pub extern "system" fn Java_de_kiyori_ep01_RuntimeChecks_nativeRun(
    mut env: JNIEnv<'_>, _class: JClass<'_>, wasm: JByteArray<'_>,
    plan_input: JByteArray<'_>, plan_output: JByteArray<'_>,
    parse_input: JByteArray<'_>, parse_output: JByteArray<'_>) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring> {
        let report = suite(&input(&mut env, &wasm)?, &input(&mut env, &plan_input)?,
            &input(&mut env, &plan_output)?, &input(&mut env, &parse_input)?,
            &input(&mut env, &parse_output)?)?;
        Ok(env.new_string(report.to_string())?.into_raw())
    }));
    match result {
        Ok(Ok(string)) => string,
        Ok(Err(error)) => { throw(&mut env, format!("EP01 native runtime: {error:#}")); ptr::null_mut() }
        Err(_) => { throw(&mut env, "EP01 native runtime panicked".into()); ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_de_kiyori_ep01_RuntimeChecks_nativeSpin(
    mut env: JNIEnv<'_>, _class: JClass<'_>, wasm: JByteArray<'_>, on_started: JObject<'_>) {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        let wasm = input(&mut env, &wasm)?;
        ensure!(!on_started.is_null(), "onStarted callback required");
        let callback = env.new_global_ref(on_started)?;
        let vm = env.get_java_vm()?;
        // Intentionally neither fuel, epochs nor call budget. Only the process watchdog stops it.
        let engine = engine(false)?;
        let module = Module::new(&engine, wasm)?;
        let mut store = Store::new(&engine, false);
        let mut linker = Linker::new(&engine);
        linker.func_wrap("arex_v1", "diagnostic",
            move |mut caller: Caller<'_, bool>, pointer: i32, length: i32| -> Result<i32> {
                let memory = caller.get_export("memory").and_then(|v| v.into_memory())
                    .ok_or_else(|| anyhow!("guest memory missing"))?;
                checked_range(pointer as u32, length as u32, memory.data_size(&caller),
                    256, "HOST_BOUNDS", "HOST_BOUNDS")?;
                if !*caller.data() {
                    *caller.data_mut() = true;
                    // The callback acknowledges a real guest-to-host call, after compilation
                    // and instantiation. It runs once on this already JVM-attached JNI thread.
                    let mut attached = vm.get_env()?;
                    attached.call_method(callback.as_obj(), "run", "()V", &[])?;
                }
                Ok(0)
            })?;
        let instance = linker.instantiate(&mut store, &module)?;
        instance.get_typed_func::<(), ()>(&mut store, "test_host_abuse")?.call(&mut store, ())?;
        bail!("unmetered guest loop unexpectedly returned")
    }));
    match result {
        Ok(Ok(())) => (),
        Ok(Err(error)) => throw(&mut env, format!("EP01 native spin: {error:#}")),
        Err(_) => throw(&mut env, "EP01 native spin panicked".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_fixture_and_hostile_exports() {
        let report = suite(include_bytes!("../../app/src/main/assets/provider.wasm"),
            include_bytes!("../../plan-input.json"), include_bytes!("../../plan-output.json"),
            include_bytes!("../../parse-input.json"), include_bytes!("../../parse-output.json")).unwrap();
        assert!(report["checks"].as_array().unwrap().len() >= 13);
    }
    #[test]
    fn guest_offsets_are_unsigned_and_bounded() {
        expect_budget(checked_range(u32::MAX, 64, MEMORY_LIMIT, 256, "HOST_BOUNDS", "HOST_BOUNDS"), "HOST_BOUNDS").unwrap();
        expect_budget(checked_range(0, u32::MAX, MEMORY_LIMIT, 256, "HOST_BOUNDS", "HOST_BOUNDS"), "HOST_BOUNDS").unwrap();
        assert_eq!(checked_range(256, 256, 512, 256, "HOST_BOUNDS", "HOST_BOUNDS").unwrap(), 256..512);
    }
}
