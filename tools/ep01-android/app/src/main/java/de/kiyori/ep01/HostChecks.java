package de.kiyori.ep01;

import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public class HostChecks {
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static void verifyCanary(File secret)throws Exception{
  byte[] expected="not-for-isolated-uid".getBytes("UTF-8"),actual=new byte[expected.length];
  try(DataInputStream in=new DataInputStream(new FileInputStream(secret))){in.readFully(actual);require(in.read()==-1,"canary length");}
  require(java.util.Arrays.equals(expected,actual),"host can read exact private canary");
 }
 static class Session implements AutoCloseable,ServiceConnection {
  final Context context;final CountDownLatch connected=new CountDownLatch(1),dead=new CountDownLatch(1);
  final BlockingQueue<Message> replies=new LinkedBlockingQueue<>();
  final HandlerThread callbackThread=new HandlerThread("ep01-host-callback");
  Messenger service,reply;IBinder binder;boolean bound;
  Session(Context context)throws Exception{
   this.context=context;callbackThread.start();
   reply=new Messenger(new Handler(callbackThread.getLooper(),m->{replies.offer(Message.obtain(m));return true;}));
   bound=context.bindService(new Intent(context,SpikeService.class),this,Context.BIND_AUTO_CREATE);
   require(bound,"bind isolated service");require(connected.await(20,TimeUnit.SECONDS),"service connection deadline");
  }
  @Override public void onServiceConnected(ComponentName name,IBinder b){binder=b;service=new Messenger(b);try{b.linkToDeath(()->dead.countDown(),0);}catch(RemoteException e){dead.countDown();}connected.countDown();}
  @Override public void onServiceDisconnected(ComponentName name){dead.countDown();}
  void send(int what,int id,Bundle data)throws RemoteException{Message m=Message.obtain();m.what=what;m.arg1=id;m.replyTo=reply;if(data!=null)m.setData(data);service.send(m);}
  Message await(int id,long seconds)throws Exception{Message m=replies.poll(seconds,TimeUnit.SECONDS);require(m!=null,"reply deadline "+id);require(m.arg1==id,"generation reply mismatch");return m;}
  JSONObject hello(File secret)throws Exception{Bundle b=new Bundle();b.putString("privatePath",secret.getAbsolutePath());send(SpikeService.HELLO,1,b);return object(await(1,15));}
  JSONObject suite()throws Exception{send(SpikeService.SUITE,2,null);return object(await(2,60));}
  @Override public void close(){if(bound){context.unbindService(this);bound=false;}callbackThread.quitSafely();}
 }
 static JSONObject object(Message m)throws Exception{Bundle b=m.getData();require("ok".equals(b.getString("status")),b.getString("value"));return new JSONObject(b.getString("value"));}
 public static JSONObject run(Context context)throws Exception{
  File secret=new File(context.getFilesDir(),"host-only-canary.txt");try(FileOutputStream out=new FileOutputStream(secret)){out.write("not-for-isolated-uid".getBytes("UTF-8"));}
  verifyCanary(secret);
  JSONArray isolation=new JSONArray();JSONObject runtime,hello,rebound;
  long bindStart=System.nanoTime();long bindMicros;
  try(Session first=new Session(context)){
   bindMicros=(System.nanoTime()-bindStart)/1000;hello=first.hello(secret);
   verifyCanary(secret);android.util.Log.i("EP01","ISOLATION "+hello);
   require(secret.getAbsolutePath().equals(hello.getString("privatePath")),"exact canary path probed");
   isolation.put("host private canary read verified before and after isolated probe");
   require(hello.getInt("uid")!=android.os.Process.myUid(),"different isolated UID");isolation.put("different isolated UID");
   require(hello.getInt("pid")!=android.os.Process.myPid(),"different process");isolation.put("different process");
   require(hello.getBoolean("privateFileDenied"),"private app file denied: "+hello);isolation.put("private app file denied");
   require(hello.getBoolean("internetPermissionDenied"),"INTERNET denied in isolated service");isolation.put("INTERNET denied");
   require(hello.getBoolean("socketDenied"),"raw socket denied");isolation.put("raw socket denied");
   runtime=first.suite();require(runtime.getJSONArray("checks").length()>=15,"complete runtime assertions");
   first.send(SpikeService.SPIN,3,null);Message started=first.await(3,15);require("started".equals(started.getData().getString("status")),"hostile loop started");
   final IBinder originalBinder=first.binder;
   ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();AtomicReference<Throwable> watchdogFailure=new AtomicReference<>();
   long killStart=System.nanoTime();
   watchdog.schedule(()->{try{first.send(SpikeService.KILL,4,null);}catch(Throwable e){watchdogFailure.set(e);}},150,TimeUnit.MILLISECONDS);
   try{require(first.dead.await(10,TimeUnit.SECONDS),"watchdog observes Binder death");require(watchdogFailure.get()==null,"watchdog control message");require(!originalBinder.isBinderAlive(),"old Binder dead");}
   finally{watchdog.shutdownNow();}
   require(first.replies.poll(200,TimeUnit.MILLISECONDS)==null,"no late result after process death");
   isolation.put("watchdog terminates unmetered guest loop; Binder death confirmed").put("no late result after death");
   runtime.put("watchdogDeathMillis",(System.nanoTime()-killStart)/1000000);
  }
  try(Session second=new Session(context)){
   rebound=second.hello(secret);require(rebound.getInt("pid")!=hello.getInt("pid"),"fresh process after termination");
   verifyCanary(secret);
   require(secret.getAbsolutePath().equals(rebound.getString("privatePath")),"exact rebound canary path");
   require(rebound.getInt("uid")!=android.os.Process.myUid()&&rebound.getBoolean("privateFileDenied")&&rebound.getBoolean("internetPermissionDenied")&&rebound.getBoolean("socketDenied"),"rebound service still isolated: "+rebound);
   JSONObject again=second.suite();require(again.getJSONArray("checks").length()>=15,"fresh service usable after crash");
   isolation.put("new process bound and full runtime suite passed again");
  }
  secret.delete();
  return new JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("abis",new JSONArray(Build.SUPPORTED_ABIS))
   .put("fingerprint",Build.FINGERPRINT).put("bindMicros",bindMicros).put("isolation",isolation).put("runtime",runtime)
   .put("firstService",hello).put("secondService",rebound).put("hostUid",android.os.Process.myUid());
 }
}
