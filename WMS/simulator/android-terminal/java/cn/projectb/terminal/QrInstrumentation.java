package cn.projectb.terminal;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Pure decode checks; no camera capture, queue writes, service starts or WMS calls. */
public class QrInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    private void require(boolean ok,String label) { if(!ok) throw new AssertionError(label); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            DataInputStream in=new DataInputStream(getTargetContext().getAssets().open("qr-selftest-gray.bin"));
            int w=in.readInt(),h=in.readInt();byte[] gray=new byte[w*h];in.readFully(gray);in.close();
            for(int rotation=0;rotation<4;rotation++) {
                byte[] actual=CameraScanActivity.nativeDecodeQr(gray,w,h);
                require(actual!=null && "SIM-STEEL-B001".equals(new String(actual,StandardCharsets.US_ASCII)),"rotation "+rotation);
                byte[] next=new byte[gray.length];
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) next[x*h+h-1-y]=gray[y*w+x];
                gray=next;int swap=w;w=h;h=swap;
            }
            byte[] blank=new byte[w*h];Arrays.fill(blank,(byte)255);
            require(CameraScanActivity.nativeDecodeQr(blank,w,h)==null,"blank must not decode");
            require(CameraScanActivity.nativeDecodeQr(new byte[16],640,480)==null,"short frame rejected");
            require(CameraScanActivity.nativeDecodeQr(null,640,480)==null,"null frame rejected");
            require(CameraScanActivity.nativeDecodeQr(blank,Integer.MAX_VALUE,480)==null,"oversize rejected");
            require(CameraScanActivity.supportedLabel(new byte[]{0})==null,"NUL label rejected");
            require(CameraScanActivity.supportedLabel(new byte[97])==null,"overlength rejected");
            result.putString("result","PASS");result.putInt("checks",10);
            result.putString("decoded","SIM-STEEL-B001");result.putString("scope","C offline decoder; queue and WMS untouched");
            finish(-1,result);
        } catch(Throwable e) { result.putString("result","FAIL "+e.toString());finish(0,result); }
    }
}
