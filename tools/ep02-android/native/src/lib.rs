//! Production AREX Wasmtime embedding for Android.
//!
//! Capability policy is intentionally split: Kotlin structural verification enforces the
//! exact import/export surface and this native layer performs Wasmtime's feature/opcode
//! validation and execution. No WASI, sockets, files, environment, or Android Context
//! are linked into the guest.
use anyhow::{anyhow, bail, ensure, Result};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jbyteArray, jlong, jstring},
    JNIEnv,
};
use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering},
        Mutex, OnceLock,
    },
    thread,
    time::{Duration, Instant},
};
use wasmtime::{
    Caller, Config, Engine, Instance, Linker, Memory, Module, Store, StoreLimits,
    StoreLimitsBuilder, Strategy, Trap,
};

const MAX_MODULE_BYTES: usize = 8 * 1024 * 1024;
const MAX_INPUT_BYTES: usize = 4 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 1024 * 1024;
const MAX_MEMORY_BYTES: usize = 32 * 1024 * 1024;
const MAX_MODULE_CACHE: usize = 8;
const MAX_DIAGNOSTIC_CALLS: u32 = 32;
const MAX_DIAGNOSTIC_BYTES: usize = 8 * 1024;
const EPOCH_TICK_MILLIS: u64 = 5;
const MAX_DEADLINE_MILLIS: u64 = 60_000;
const CANCEL_EPOCH_JUMP: usize = (MAX_DEADLINE_MILLIS / EPOCH_TICK_MILLIS + 2) as usize;

static ENGINE: OnceLock<Engine> = OnceLock::new();
static ENGINE_INIT: Mutex<()> = Mutex::new(());
static TICKER_STARTED: AtomicBool = AtomicBool::new(false);
static MODULES: OnceLock<Mutex<HashMap<String, Module>>> = OnceLock::new();
static ACTIVE_INVOCATION: AtomicU64 = AtomicU64::new(0);
static INTERRUPT_REASON: AtomicU8 = AtomicU8::new(0);
static LAST_METRICS: OnceLock<Mutex<String>> = OnceLock::new();

#[derive(Default)]
struct State {
    limits: StoreLimits,
    diagnostic_calls: u32,
    diagnostic_bytes: usize,
}

struct Host {
    store: Store<State>,
    instance: Instance,
    memory: Memory,
    output_limit: usize,
}

fn build_engine() -> Result<Engine> {
    let mut config = Config::new();
    config
        .strategy(Strategy::Cranelift)
        .consume_fuel(true)
        .epoch_interruption(true)
        .wasm_gc(false)
        .wasm_simd(false)
        .wasm_relaxed_simd(false)
        .wasm_bulk_memory(false)
        .wasm_multi_value(false)
        .wasm_multi_memory(false)
        .wasm_memory64(false)
        .wasm_extended_const(false)
        .wasm_stack_switching(false)
        .wasm_shared_everything_threads(false)
        .wasm_wide_arithmetic(false)
        .wasm_tail_call(false)
        .wasm_custom_page_sizes(false)
        .wasm_branch_hinting(false)
        .signals_based_traps(false)
        .memory_reservation(MAX_MEMORY_BYTES as u64)
        .memory_guard_size(0)
        .memory_reservation_for_growth(0);
    #[allow(deprecated)]
    {
        config.wasm_legacy_exceptions(false);
    }
    // Cargo deliberately omits Wasmtime's gc, component-model, threads, async,
    // pooling-allocator, cache, profiling, and WASI crates. Reference-types,
    // typed function references, exceptions, component model, and threads
    // therefore cannot be compiled by this embedding at all.
    Ok(Engine::new(&config)?)
}

fn engine() -> Result<&'static Engine> {
    if ENGINE.get().is_none() {
        let _guard = ENGINE_INIT.lock().map_err(|_| anyhow!("engine init lock poisoned"))?;
        if ENGINE.get().is_none() {
            let value = build_engine()?;
            let ticker_engine = value.clone();
            let _ = ENGINE.set(value);
            if !TICKER_STARTED.swap(true, Ordering::AcqRel) {
                thread::Builder::new()
                    .name("arex-epoch".into())
                    .spawn(move || loop {
                        thread::sleep(Duration::from_millis(EPOCH_TICK_MILLIS));
                        ticker_engine.increment_epoch();
                    })?;
            }
        }
    }
    ENGINE.get().ok_or_else(|| anyhow!("engine unavailable"))
}

fn modules() -> &'static Mutex<HashMap<String, Module>> {
    MODULES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn metrics() -> &'static Mutex<String> {
    LAST_METRICS.get_or_init(|| Mutex::new("{}".to_owned()))
}

fn checked_range(pointer: u32, length: u32, memory_size: usize, cap: usize) -> Result<std::ops::Range<usize>> {
    let length = length as usize;
    ensure!(length <= cap, "OUTPUT_LIMIT");
    let start = pointer as usize;
    let end = start.checked_add(length).ok_or_else(|| anyhow!("MEMORY_LIMIT"))?;
    ensure!(end <= memory_size, "MEMORY_LIMIT");
    Ok(start..end)
}

fn state(memory_bytes: usize) -> State {
    State {
        limits: StoreLimitsBuilder::new()
            .memory_size(memory_bytes)
            .instances(1)
            .memories(1)
            .tables(1)
            .table_elements(4096)
            .build(),
        diagnostic_calls: 0,
        diagnostic_bytes: 0,
    }
}

impl Host {
    fn new(engine: &Engine, module: &Module, memory_bytes: usize, fuel: u64, deadline_millis: u64, output_limit: usize) -> Result<Self> {
        let mut store = Store::new(engine, state(memory_bytes));
        store.limiter(|state| &mut state.limits);
        store.set_fuel(fuel)?;
        let ticks = deadline_millis.saturating_add(EPOCH_TICK_MILLIS - 1) / EPOCH_TICK_MILLIS;
        store.set_epoch_deadline(ticks.max(1));
        store.epoch_deadline_trap();

        let mut linker = Linker::new(engine);
        linker.func_wrap(
            "arex_v1",
            "diagnostic",
            |mut caller: Caller<'_, State>, pointer: i32, length: i32| -> wasmtime::Result<i32> {
                let next_calls = caller.data().diagnostic_calls.saturating_add(1);
                if next_calls > MAX_DIAGNOSTIC_CALLS {
                    return Err(wasmtime::Error::msg("IMPORT_LIMIT"));
                }
                let memory = caller
                    .get_export("memory")
                    .and_then(|value| value.into_memory())
                    .ok_or_else(|| wasmtime::Error::msg("ABI_MISMATCH"))?;
                let range = checked_range(
                    pointer as u32,
                    length as u32,
                    memory.data_size(&caller),
                    256,
                )
                .map_err(|_| wasmtime::Error::msg("IMPORT_LIMIT"))?;
                let next_bytes = caller.data().diagnostic_bytes.saturating_add(range.len());
                if next_bytes > MAX_DIAGNOSTIC_BYTES {
                    return Err(wasmtime::Error::msg("IMPORT_LIMIT"));
                }
                let mut scratch = [0u8; 256];
                memory
                    .read(&caller, range.start, &mut scratch[..range.len()])
                    .map_err(|error| wasmtime::Error::msg(error.to_string()))?;
                caller.data_mut().diagnostic_calls = next_calls;
                caller.data_mut().diagnostic_bytes = next_bytes;
                Ok(0)
            },
        )?;
        let instance = linker.instantiate(&mut store, module)?;
        let memory = instance
            .get_memory(&mut store, "memory")
            .ok_or_else(|| anyhow!("ABI_MISMATCH"))?;
        Ok(Self {
            store,
            instance,
            memory,
            output_limit,
        })
    }

    fn call(&mut self, export_name: &str, input: &[u8]) -> Result<Vec<u8>> {
        ensure!(input.len() <= MAX_INPUT_BYTES, "INVALID_INPUT");
        let alloc = self
            .instance
            .get_typed_func::<i32, i32>(&mut self.store, "arex_alloc")
            .map_err(|_| anyhow!("ABI_MISMATCH"))?;
        let pointer = alloc.call(&mut self.store, input.len() as i32)?;
        let input_range = checked_range(
            pointer as u32,
            input.len() as u32,
            self.memory.data_size(&self.store),
            MAX_INPUT_BYTES,
        )
        .map_err(|_| anyhow!("MEMORY_LIMIT"))?;
        self.memory.write(&mut self.store, input_range.start, input)?;

        let function = self
            .instance
            .get_typed_func::<(i32, i32), i64>(&mut self.store, export_name)
            .map_err(|_| anyhow!("ABI_MISMATCH"))?;
        let packed = function.call(&mut self.store, (pointer, input.len() as i32))? as u64;
        let output_range = checked_range(
            (packed >> 32) as u32,
            packed as u32,
            self.memory.data_size(&self.store),
            self.output_limit,
        )?;
        let mut output = vec![0u8; output_range.len()];
        self.memory.read(&self.store, output_range.start, &mut output)?;

        let free = self
            .instance
            .get_typed_func::<(i32, i32), ()>(&mut self.store, "arex_free")
            .map_err(|_| anyhow!("ABI_MISMATCH"))?;
        free.call(&mut self.store, (pointer, input.len() as i32))?;
        Ok(output)
    }
}

fn module_for(digest: &str, bytes: &[u8]) -> Result<(Module, bool, u128)> {
    ensure!(digest.len() == 64 && digest.bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()), "INVALID_INPUT");
    let mut cache = modules().lock().map_err(|_| anyhow!("module cache lock poisoned"))?;
    if let Some(module) = cache.get(digest) {
        return Ok((module.clone(), true, 0));
    }
    ensure!(!bytes.is_empty() && bytes.len() <= MAX_MODULE_BYTES, "INVALID_INPUT");
    let started = Instant::now();
    let module = Module::new(engine()?, bytes)?;
    let compile_micros = started.elapsed().as_micros();
    if cache.len() >= MAX_MODULE_CACHE {
        cache.clear();
    }
    cache.insert(digest.to_owned(), module.clone());
    Ok((module, false, compile_micros))
}

fn read_bytes(env: &mut JNIEnv<'_>, array: &JByteArray<'_>, cap: usize) -> Result<Vec<u8>> {
    let length = env.get_array_length(array)? as usize;
    ensure!(length <= cap, "JNI_INPUT_LIMIT");
    Ok(env.convert_byte_array(array)?)
}

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>, cap: usize) -> Result<String> {
    let value: String = env.get_string(value)?.into();
    ensure!(!value.is_empty() && value.len() <= cap, "JNI_STRING_LIMIT");
    Ok(value)
}

fn throw(env: &mut JNIEnv<'_>, message: String) {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/RuntimeException", message);
    }
}

fn validate_module(bytes: &[u8]) -> Result<()> {
    ensure!(!bytes.is_empty() && bytes.len() <= MAX_MODULE_BYTES, "INVALID_INPUT");
    Module::validate(engine()?, bytes).map_err(Into::into)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_axiel7_anihyou_release_data_extension_WasmtimeNativeBridge_nativeValidate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    module: JByteArray<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring> {
        let bytes = read_bytes(&mut env, &module, MAX_MODULE_BYTES)?;
        match validate_module(&bytes) {
            Ok(()) => Ok(ptr::null_mut()),
            Err(error) => Ok(env.new_string(format!("{error:#}"))?.into_raw()),
        }
    }));
    match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            throw(&mut env, format!("NATIVE_VALIDATOR:{error:#}"));
            ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "NATIVE_VALIDATOR:panic".into());
            ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_axiel7_anihyou_release_data_extension_WasmtimeNativeBridge_nativeExecute(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    digest: JString<'_>,
    module: JByteArray<'_>,
    export_name: JString<'_>,
    input: JByteArray<'_>,
    max_output: jlong,
    memory_bytes: jlong,
    fuel: jlong,
    deadline_millis: jlong,
    invocation_id: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jbyteArray> {
        ensure!(invocation_id > 0, "INVALID_INPUT");
        ensure!(max_output > 0 && max_output as usize <= MAX_OUTPUT_BYTES, "OUTPUT_LIMIT");
        ensure!(memory_bytes > 0 && memory_bytes as usize <= MAX_MEMORY_BYTES, "MEMORY_LIMIT");
        ensure!(fuel > 0, "INVALID_INPUT");
        ensure!(deadline_millis > 0 && deadline_millis <= MAX_DEADLINE_MILLIS as jlong, "INVALID_INPUT");
        let digest = read_string(&mut env, &digest, 64)?;
        let export_name = read_string(&mut env, &export_name, 64)?;
        ensure!(
            matches!(
                export_name.as_str(),
                "plan_requests" | "parse_responses" | "plan_navigation" | "parse_navigation"
            ),
            "ABI_MISMATCH"
        );
        let module_bytes = read_bytes(&mut env, &module, MAX_MODULE_BYTES)?;
        let input = read_bytes(&mut env, &input, MAX_INPUT_BYTES)?;
        let native_started = Instant::now();
        let (module, cache_hit, compile_micros) = module_for(&digest, &module_bytes)?;

        let engine = engine()?;
        let mut host = Host::new(
            engine,
            &module,
            memory_bytes as usize,
            fuel as u64,
            deadline_millis as u64,
            max_output as usize,
        )?;
        INTERRUPT_REASON.store(0, Ordering::Release);
        ACTIVE_INVOCATION.store(invocation_id as u64, Ordering::Release);
        let guest_started = Instant::now();
        let call_result = host.call(&export_name, &input);
        let guest_elapsed = guest_started.elapsed();
        let interrupted = call_result
            .as_ref()
            .err()
            .and_then(|error| error.downcast_ref::<Trap>())
            .is_some_and(|trap| *trap == Trap::Interrupt);
        let _ = ACTIVE_INVOCATION.compare_exchange(
            invocation_id as u64,
            0,
            Ordering::AcqRel,
            Ordering::Acquire,
        );
        let reason = INTERRUPT_REASON.swap(0, Ordering::AcqRel);
        let output = match call_result {
            Ok(value) => value,
            Err(error) if reason == 1 && interrupted => bail!("CANCELLED:{error:#}"),
            Err(error) if interrupted => bail!("DEADLINE:{error:#}"),
            Err(error) => return Err(error),
        };
        let native_micros = native_started.elapsed().as_micros();
        let guest_micros = guest_elapsed.as_micros();
        let text = format!(
            "{{\"runtime\":\"Wasmtime 48.0.3 Cranelift\",\"cacheHit\":{},\"compileMicros\":{},\"nativeMicros\":{},\"guestMicros\":{},\"diagnosticCalls\":{},\"diagnosticBytes\":{}}}",
            cache_hit,
            compile_micros,
            native_micros,
            guest_micros,
            host.store.data().diagnostic_calls,
            host.store.data().diagnostic_bytes
        );
        *metrics().lock().map_err(|_| anyhow!("metrics lock poisoned"))? = text;
        Ok(env.byte_array_from_slice(&output)?.into_raw())
    }));
    match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            throw(&mut env, format!("AREX_RUNTIME:{error:#}"));
            ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "AREX_RUNTIME:panic".into());
            ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_axiel7_anihyou_release_data_extension_WasmtimeNativeBridge_nativeCancel(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    invocation_id: jlong,
) -> jboolean {
    if invocation_id <= 0 || ACTIVE_INVOCATION.load(Ordering::Acquire) != invocation_id as u64 {
        return 0;
    }
    INTERRUPT_REASON.store(1, Ordering::Release);
    if let Ok(engine) = engine() {
        for _ in 0..CANCEL_EPOCH_JUMP {
            engine.increment_epoch();
        }
        1
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_axiel7_anihyou_release_data_extension_WasmtimeNativeBridge_nativeMetrics(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let value = metrics()
        .lock()
        .map(|value| value.clone())
        .unwrap_or_else(|_| "{}".to_owned());
    match env.new_string(value) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            throw(&mut env, format!("METRICS:{error}"));
            ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_axiel7_anihyou_release_data_extension_WasmtimeNativeBridge_nativeRuntimeVersion(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    match env.new_string("wasmtime-48.0.3-cranelift-android") {
        Ok(value) => value.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_axiel7_anihyou_release_data_extension_WasmtimeNativeBridge_nativeClearModuleCache(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    if let Ok(mut cache) = modules().lock() {
        cache.clear();
    }
}
