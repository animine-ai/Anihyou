package de.kiyori.ep01;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

public class SpikeActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
  Button start=new Button(this);start.setText("Run isolated WASM tests");TextView output=new TextView(this);output.setText("Fixture-only EP01 runtime test. No provider requests.");
  ScrollView scroll=new ScrollView(this);scroll.addView(output);layout.addView(start);layout.addView(scroll);setContentView(layout);
  start.setOnClickListener(v->{start.setEnabled(false);output.setText("Running…");new Thread(()->{
   String text;try{text=HostChecks.run(this).toString(2);}catch(Throwable e){text=android.util.Log.getStackTraceString(e);}String finalText=text;
   runOnUiThread(()->{output.setText(finalText);start.setEnabled(true);});
  },"ep01-host-tests").start();});
 }
}
