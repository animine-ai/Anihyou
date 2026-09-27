package de.kiyori.ep01;
import android.content.Context;
import android.os.Debug;
import org.json.*;
import java.io.*;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class RuntimeChecks {
 static final int OUTPUT_LIMIT=1048576;
 static Context context;
 static JSONArray checks;
 static class Budget extends RuntimeException { private static final long serialVersionUID=1L; Budget(String s){super(s);} }
 static class Host {
  final AtomicInteger calls=new AtomicInteger();
  final AtomicLong instructions=new AtomicLong();
  final AtomicBoolean cancel=new AtomicBoolean();
  final Instance instance;
  Host(long budget,long deadline,boolean listener) throws Exception {
   HostFunction diagnostic=new HostFunction("arex_v1","diagnostic",
    FunctionType.of(List.of(ValType.I32,ValType.I32),List.of(ValType.I32)),(i,a)->{
     if(calls.incrementAndGet()>32) throw new Budget("HOST_CALL_LIMIT");
     long p=a[0]&0xffffffffL,n=a[1]&0xffffffffL;
     if(n>256 || p+n>(long)i.memory().pages()*65536) throw new Budget("HOST_BOUNDS");
     i.memory().readBytes((int)p,(int)n);
     return new long[]{0};});
   var builder=Instance.builder(Parser.parse(asset("provider.wasm")))
    .withImportValues(ImportValues.builder().addFunction(diagnostic).build())
    .withMemoryLimits(new MemoryLimits(4,64));
   if(listener) builder.withUnsafeExecutionListener((instruction,stack)->{
    if(cancel.get()) throw new Budget("CANCELLED");
    if(System.nanoTime()>deadline) throw new Budget("DEADLINE");
    if(instructions.incrementAndGet()>budget) throw new Budget("FUEL");
   });
   instance=builder.build();
  }
  byte[] read(long result){
   long p=result>>>32,n=result&0xffffffffL;
   if(n>OUTPUT_LIMIT) throw new Budget("OUTPUT_LIMIT");
   if(p+n>(long)instance.memory().pages()*65536) throw new Budget("OUTPUT_BOUNDS");
   return instance.memory().readBytes((int)p,(int)n);
  }
  byte[] call(String fn,byte[] input){
   if(input.length>65536)throw new Budget("INPUT_LIMIT");
   long p=instance.export("arex_alloc").apply(input.length)[0];
   instance.memory().write((int)p,input);
   return read(instance.export(fn).apply(p,input.length)[0]);
  }
 }
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static void pass(String test){checks.put(test); android.util.Log.i("EP01", "PASS "+test);}
 static void expect(String msg,Runnable f){try{f.run();throw new AssertionError("expected "+msg);}catch(Budget e){check(msg.equals(e.getMessage()),e.toString());}pass(msg);}
 static Host fresh()throws Exception{return new Host(2000000,Long.MAX_VALUE,true);}
 public static JSONObject run(Context app)throws Exception{
  context=app; checks=new JSONArray();
  long start=System.nanoTime();
  long heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
  long cold=System.nanoTime();
  Host h=fresh();
  long coldMicros=(System.nanoTime()-cold)/1000;
  for(String operation:List.of("plan","parse")){
   byte[] input=asset(operation+"-input.json");
   byte[] expected=asset(operation+"-output.json");
   String fn=operation.equals("plan")?"plan_requests":"parse_responses";
   check(Arrays.equals(h.call(fn,input),expected),operation);
   check(Arrays.equals(h.call(fn,input),expected),"determinism");
   byte[] wrong=new String(input,java.nio.charset.StandardCharsets.UTF_8).replace("\"schemaVersion\":1","\"schemaVersion\":2").getBytes(java.nio.charset.StandardCharsets.UTF_8);
   check(new String(h.call(fn,wrong),java.nio.charset.StandardCharsets.UTF_8).contains("INVALID_INPUT"),"version");
   pass(operation+" JSON roundtrip, deterministic repeat, incompatible schema rejected");
  }
  check(h.calls.get()==4,"host import");pass("bounded host import");
  check(h.instance.export("test_grow").apply()[0]==-1,"memory grow");
  check(h.instance.memory().pages()==4,"memory unchanged");pass("4 MiB memory cap");
  expect("OUTPUT_LIMIT",()->h.read(h.instance.export("test_oversize").apply()[0]));
  expect("OUTPUT_BOUNDS",()->h.read(h.instance.export("test_oob").apply()[0]));
  Host fuel=new Host(10000,Long.MAX_VALUE,true);
  expect("FUEL",()->fuel.instance.export("test_spin").apply());
  Host timeout=new Host(Long.MAX_VALUE,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(150),true);
  long started=System.nanoTime();expect("DEADLINE",()->timeout.instance.export("test_spin").apply());
  check(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(2),"timeout latency");
  Host cancellation=new Host(Long.MAX_VALUE,Long.MAX_VALUE,true);
  Thread cancelThread=new Thread(()->{try{Thread.sleep(20);}catch(InterruptedException e){throw new RuntimeException(e);}cancellation.cancel.set(true);});
  cancelThread.start();expect("CANCELLED",()->cancellation.instance.export("test_spin").apply());cancelThread.join();
  Host abuse=fresh();expect("HOST_CALL_LIMIT",()->abuse.instance.export("test_host_abuse").apply());
  Host crash=fresh();boolean trapped=false;try{crash.instance.export("test_crash").apply();}catch(com.dylibso.chicory.runtime.TrapException e){trapped=true;}check(trapped,"crash trap");pass("guest crash isolated");
  check(Arrays.equals(fresh().call("plan_requests",asset("plan-input.json")),asset("plan-output.json")),"recovery");pass("fresh invocation after crash");
  long warmStart=System.nanoTime();
  for(int n=0;n<10;n++) check(Arrays.equals(fresh().call("plan_requests",asset("plan-input.json")),asset("plan-output.json")),"repeated fresh instance");
  pass("10 repeated fresh instances");
  return new JSONObject().put("checks",checks).put("coldInstantiationMicros",coldMicros)
    .put("tenFreshCallsMicros",(System.nanoTime()-warmStart)/1000).put("elapsedMillis",(System.nanoTime()-start)/1000000)
    .put("javaHeapBefore",heapBefore).put("javaHeapAfter",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
    .put("nativeHeapAllocated",Debug.getNativeHeapAllocatedSize()).put("runtime","Chicory 1.7.5 interpreter");
 }
 static byte[] asset(String name)throws IOException {
  try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
   byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>1048576)throw new IOException("asset bound");}return out.toByteArray();
  }
 }
 static void spinWithoutListener(Context app)throws Exception {
  context=app;new Host(Long.MAX_VALUE,Long.MAX_VALUE,false).instance.export("test_spin").apply();
 }
}
