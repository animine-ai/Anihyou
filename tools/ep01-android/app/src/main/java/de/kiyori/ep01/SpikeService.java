package de.kiyori.ep01;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public class SpikeService extends Service {
 static final int HELLO=1, SUITE=2, SPIN=3, KILL=4;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private Messenger endpoint;
 @Override public void onCreate(){super.onCreate();endpoint=new Messenger(new Handler(getMainLooper(),this::receive));}
 @Override public IBinder onBind(Intent intent){return endpoint.getBinder();}
 private boolean receive(Message message){
  final Messenger reply=message.replyTo;final int id=message.arg1;
  if(message.what==KILL){android.os.Process.killProcess(android.os.Process.myPid());return true;}
  if(message.what==HELLO){
   try{
    int privateErrno=0,socketErrno=0;FileDescriptor privateFile=null,socket=null;
    try{privateFile=Os.open(message.getData().getString("privatePath"),OsConstants.O_RDONLY,0);}catch(ErrnoException denied){privateErrno=denied.errno;}finally{if(privateFile!=null)Os.close(privateFile);}
    try{socket=Os.socket(OsConstants.AF_INET,OsConstants.SOCK_STREAM,0);}catch(ErrnoException denied){socketErrno=denied.errno;}finally{if(socket!=null)Os.close(socket);}
    boolean privateDenied=privateErrno==OsConstants.EACCES||privateErrno==OsConstants.EPERM;
    boolean socketDenied=socketErrno==OsConstants.EACCES||socketErrno==OsConstants.EPERM;
    JSONObject report=new JSONObject().put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
      .put("privateFileDenied",privateDenied).put("privateFileErrno",privateErrno).put("socketDenied",socketDenied).put("socketErrno",socketErrno)
      .put("internetPermissionDenied",checkSelfPermission("android.permission.INTERNET")!=PackageManager.PERMISSION_GRANTED);
    respond(reply,id,"ok",report.toString());
   }catch(Throwable e){respond(reply,id,"error",e.toString());}
   return true;
  }
  if(message.what==SUITE||message.what==SPIN){final int op=message.what;
   worker.execute(()->{try{
    if(op==SPIN){RuntimeChecks.spinWithoutListener(this,()->respond(reply,id,"started","guest import reached"));respond(reply,id,"late","unexpected loop return");}
    else respond(reply,id,"ok",RuntimeChecks.run(this).toString());
   }catch(Throwable e){respond(reply,id,"error",android.util.Log.getStackTraceString(e));}});
   return true;
  }
  return false;
 }
 static void respond(Messenger reply,int id,String status,String value){
  try{Message m=Message.obtain();m.arg1=id;Bundle b=new Bundle();b.putString("status",status);b.putString("value",value);m.setData(b);reply.send(m);}catch(RemoteException ignored){}
 }
 @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}
}
