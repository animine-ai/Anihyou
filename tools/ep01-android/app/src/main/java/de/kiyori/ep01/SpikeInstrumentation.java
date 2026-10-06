package de.kiyori.ep01;
import android.app.Instrumentation;
import android.os.Bundle;

public class SpikeInstrumentation extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
  String json=HostChecks.run(getTargetContext()).toString();
  result.putString("ep01",json);result.putString("stream","EP01_ANDROID_PASS\n"+json+"\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream","EP01_ANDROID_FAIL\n"+android.util.Log.getStackTraceString(e));finish(0,result);}}
}
