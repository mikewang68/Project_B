package cn.projectb.terminal;
import android.app.Instrumentation;
import android.os.Bundle;
public class ProtocolInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{MainActivity.ensureInit(getTargetContext());int code=MainActivity.nativeSelfTest();result.putInt("c_selftest",code);result.putString("state",MainActivity.nativeSnapshot());result.putString("result",code==0?"PASS":"FAIL");finish(code==0?-1:0,result);}catch(Throwable e){result.putString("result","FAIL "+e.toString());finish(0,result);}}
}
