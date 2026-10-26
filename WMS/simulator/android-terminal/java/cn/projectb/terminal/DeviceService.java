package cn.projectb.terminal;
import android.app.*;
import android.content.Intent;
import android.os.IBinder;

public class DeviceService extends Service {
    @Override public void onCreate(){super.onCreate();MainActivity.ensureInit(this);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("device-sim","设备模拟服务",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&"STOP".equals(intent.getAction())){MainActivity.nativeStop();stopForeground(true);stopSelf();return START_NOT_STICKY;}
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DeviceService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(this,"device-sim").setContentTitle("WMS 设备模拟运行中").setContentText("C协议服务 · 点击返回终端").setSmallIcon(cn.projectb.terminal.R.drawable.ic_terminal).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止服务",stop).build()).build();
        startForeground(1,note);String result=MainActivity.nativeStart(intent!=null&&intent.getBooleanExtra("lan",false));
        if(result.contains("失败")||result.contains("不能")){MainActivity.nativeStop();stopForeground(true);stopSelf();}
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){MainActivity.nativeStop();super.onDestroy();}
    @Override public void onTaskRemoved(Intent intent){stopSelf();}
    @Override public IBinder onBind(Intent intent){return null;}
}
