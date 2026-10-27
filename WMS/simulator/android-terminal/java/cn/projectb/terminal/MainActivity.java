package cn.projectb.terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.util.Collections;

/** Android shell only: rendering, text entry, lifecycle and notification.
 * Device models, frames, queue, persistence and socket servers are compiled C. */
public class MainActivity extends Activity {
    static { System.loadLibrary("wms_protocol"); }
    public static native String nativeInit(String directory);
    public static native String nativeStart(boolean lan);
    public static native void nativeStop();
    public static native String nativeScan(String tag);
    public static native String nativeConfigure(double gross,double tare,double level,double pressure,double flow,int flags);
    public static native String nativeSnapshot();
    public static native int nativeSelfTest();
    public static native String nativeLog();
    public static native String nativeEeConfigure(double pressurePa,double k,double density,boolean calibrated,boolean error);
    public static native String nativeVegaConfigure(double pv,int unit,double empty,double full,boolean distance,boolean calibrated,boolean invalid,boolean nonfinite);
    public static native String nativeReferenceScan(String tag);
    private static boolean initialized;
    public static synchronized String ensureInit(Context c) {
        if (initialized) return "C 核心已就绪";
        String result=nativeInit(c.getFilesDir().getAbsolutePath());
        initialized=!result.contains("损坏"); return result;
    }
    private final int ink=Color.rgb(18,62,74), muted=Color.rgb(91,108,117), teal=Color.rgb(14,116,119);
    private final Handler handler=new Handler();
    private LinearLayout body;
    private TextView live, message;
    private EditText tag, gross,tare,level,pressure,flow;
    private EditText eePressure,eeK,eeDensity;
    private CheckBox eeCalibrated,eeError;
    private EditText vegaPv,vegaUnit,vegaEmpty,vegaFull;
    private CheckBox vegaDistance,vegaCalibrated,vegaInvalid,vegaNonfinite;
    private int instrument=0;
    private int scanMode=0;
    private TextView referenceLive;
    private CheckBox stable,occupied,person,blockage,leakage,fault;
    private int page=0;
    private String currentTag="SIM-STEEL-B001";
    private final Runnable refresh=new Runnable(){public void run(){updateLive();handler.postDelayed(this,1000);}};
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(ink);getWindow().setNavigationBarColor(ink);
        ensureInit(this);build();
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==202 && result==RESULT_OK && data!=null){
            String value=data.getStringExtra("SCAN_TEXT");
            if(value!=null){currentTag=value;page=0;build();tag.setText(value);show("二维码："+value+"\n"+nativeScan(value));}
        }
    }
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private LinearLayout column(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}
    private void heading(LinearLayout parent,String title,String note){TextView h=text(title,19,ink);h.setTypeface(null,Typeface.BOLD);parent.addView(h);if(!note.isEmpty())parent.addView(text(note,13,muted));}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(16),dp(12),dp(16),dp(14));c.setBackground(background(Color.WHITE,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(12));body.addView(c,p);return c;}
    private Button button(LinearLayout parent,String title,boolean primary,View.OnClickListener click){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?Color.WHITE:ink);b.setBackground(background(primary?teal:Color.rgb(231,239,241),10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(6),0,dp(3));parent.addView(b,p);b.setOnClickListener(click);return b;}
    private EditText input(LinearLayout parent,String label,String value,boolean numeric){parent.addView(text(label,13,muted));EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(17);e.setText(value);e.setTextColor(ink);e.setInputType(numeric?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);parent.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));return e;}
    private CheckBox check(LinearLayout parent,String label,boolean value){CheckBox c=new CheckBox(this);c.setText(label);c.setTextSize(14);c.setTextColor(ink);c.setChecked(value);parent.addView(c);return c;}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(243,246,248));
        body=column();body.setPadding(dp(18),dp(14),dp(18),dp(24));scroll.addView(body);setContentView(scroll);
        TextView eyebrow=text("PROJECT B   /   周绪敬",12,teal);eyebrow.setTypeface(null,Typeface.BOLD);body.addView(eyebrow);
        TextView title=text("设备模拟终端",28,ink);title.setTypeface(null,Typeface.BOLD);body.addView(title);
        body.addView(text("C 原生协议核心 · Android 设备端",13,muted));
        live=text("读取设备状态…",14,ink);live.setPadding(dp(12),dp(12),dp(12),dp(12));live.setBackground(background(Color.rgb(223,238,236),10));body.addView(live);
        LinearLayout tabs=new LinearLayout(this);String[] names={"扫码","称重","工艺","仪表","连接"};
        for(int i=0;i<5;i++){final int n=i;Button b=new Button(this);b.setText(names[i]);b.setTextColor(i==page?teal:muted);b.setTextSize(14);b.setAllCaps(false);tabs.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));b.setOnClickListener(v->{page=n;build();});}body.addView(tabs);
        try{JSONObject s=new JSONObject(nativeSnapshot());if(page==0)scanner();else if(page==1)weighing(s);else if(page==2)process(s);else if(page==3)instruments(s);else connection();}catch(Exception e){body.addView(text("读取状态失败，请重新打开应用",15,Color.RED));}
        message=text("仅生成模拟设备数据；收货确认在 WMS 中完成。",13,muted);body.addView(message);updateLive();
    }
    private void scanner(){
        referenceLive=null;
        LinearLayout selector=card();
        button(selector,"业务联调（持久队列）",scanMode==0,v->{scanMode=0;build();});
        button(selector,"原厂握手参考（不入队）",scanMode==1,v->{scanMode=1;build();});
        if(scanMode==1){scannerReference();return;}
        LinearLayout c=card();heading(c,"手持扫码","EQ-EXT-06 · 参考 DS2208 的串口数据格式");
        button(c,"摄像头扫二维码",true,v->startActivityForResult(new Intent(this,CameraScanActivity.class),202));
        tag=input(c,"条码／模拟标签",currentTag,false);
        button(c,"读取标签并保存",true,v->show(nativeScan(tag.getText().toString().trim())));
        button(c,"重复读取同一标签",false,v->show(nativeScan(tag.getText().toString().trim())));
        button(c,"模拟未知标签",false,v->show(nativeScan("SIM-UNKNOWN-001")));
        LinearLayout d=card();heading(d,"有限样例","点击选择标签，再执行读取；同一标签可用于防重测试。");
        Spinner kinds=new Spinner(this);String[] labels={"钢材 · 捆","袋装水泥 · 袋","袋装粉煤灰 · 袋","机械配件 · 件","散装粉煤灰 · 批次","散装砂石 · 批次"};
        String[] codes={"SIM-STEEL-B001","SIM-CEMENT-BAG-001","SIM-ASH-BAG-001","SIM-PART-001","SIM-ASH-LOAD-001","SIM-GRAVEL-LOAD-001"};
        kinds.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));d.addView(kinds);
        kinds.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){boolean first=true;public void onItemSelected(android.widget.AdapterView<?> a,View v,int p,long id){if(first){first=false;return;}currentTag=codes[p];tag.setText(currentTag);}public void onNothingSelected(android.widget.AdapterView<?> a){}});
        d.addView(text("摄像头支持二维码，识别后保存一条扫码，重复扫码需再次点击。标签为1–96位可打印ASCII。原始输出为 ASCII + CR/LF；网关保存后回 ACK才移除待发项。离线识别，不上传相机画面；RFID及一维条码摄像头识别尚未接入。",13,muted));
    }
    private void scannerReference(){
        LinearLayout c=card();heading(c,"DS2208 握手参考","独立19065端口 · 原厂RS-232 ACK/NAK行为子集");
        EditText referenceTag=input(c,"参考标签（仅本次内存事务）","SIM-HANDSHAKE-001",false);
        button(c,"发送一次参考标签",true,v->show(nativeReferenceScan(referenceTag.getText().toString())));
        referenceLive=text("等待独立参考客户端连接",15,ink);c.addView(referenceLive);
        c.addView(text("先在连接页启动服务，再让scanner_reference_client.py连接19065，最后点发送。ACK结束本次事务；NAK有限重发；2秒无有效ACK/NAK即丢弃，不重连补发。完成后连接关闭，下一次须重新连接。",13,muted));
        c.addView(text("原厂手册MN-002874-14EN，9-14／9-16。本参考将三次失败解释为总发送3次（含首次）；原文没有明确首发计数，仍待实机确认。错误显示在页面及独立记录中，未模拟原厂蜂鸣、电气握手或SSI。",13,muted));
        c.addView(text("该模式没有摄像头入口、不写业务扫码队列。正常业务扫码请切回持久队列模式。原厂握手参考的丢弃和WMS收货无关。",13,muted));
    }
    private void weighing(JSONObject s)throws Exception{
        LinearLayout c=card();heading(c,"汽车衡仪表","EQ-IMG-05 · IND231/236 连续输出参考配置");
        gross=input(c,"毛重（整数 kg）",s.getString("gross"),true);tare=input(c,"皮重（整数 kg）",s.getString("tare"),true);
        stable=check(c,"重量稳定",(s.getInt("flags")&1)!=0);fault=check(c,"模拟仪表超量程",(s.getInt("flags")&32)!=0);
        button(c,"保存称重参数",true,v->apply());
        c.addView(text("净重由 C 核心计算。默认 32500 − 12500 = 20000 kg，即20 t。重量稳定和皮重来自原始帧；车辆、工单和收货确认由业务系统关联。",13,muted));
    }
    private void process(JSONObject s)throws Exception{
        LinearLayout c=card();heading(c,"筒仓、拆箱房、空气斜槽","三条独立只读 Modbus TCP 服务；采用我方实验 PLC 点表。");
        level=input(c,"筒仓料位（%）",s.getString("level"),true);pressure=input(c,"输送压力（kPa）",s.getString("pressure"),true);flow=input(c,"输送空气流量（m³/h）",s.getString("flow"),true);
        int flags=s.getInt("flags");occupied=check(c,"拆箱房占用",(flags&2)!=0);person=check(c,"拆箱房人员在场",(flags&4)!=0);blockage=check(c,"空气斜槽堵塞",(flags&8)!=0);leakage=check(c,"空气斜槽泄漏",(flags&16)!=0);
        button(c,"保存工艺状态",true,v->apply());c.addView(text("这些地址属于实验工程配置，需用现场 PLC 点表替换。实验过程量标记 UNCERTAIN，不据此宣称现场采集通过；写寄存器命令全部拒绝。",13,muted));
    }
    private void instruments(JSONObject s)throws Exception{
        LinearLayout selector=card();
        button(selector,"EE600 差压",instrument==0,v->{instrument=0;build();});
        button(selector,"VEGAPULS 6X 雷达",instrument==1,v->{instrument=1;build();});
        if(instrument==0)ee600(s);else vega(s);
    }
    private void vega(JSONObject s)throws Exception{
        LinearLayout c=card();heading(c,"VEGAPULS 6X 雷达参考","筒仓子设备 · PV单位、无效状态与距离标定");
        vegaPv=input(c,"PV读数（须核对物理量和单位）",s.getString("vega_pv"),true);
        vegaUnit=input(c,"单位代码（45=m，44=ft，43=m³）",s.getString("vega_unit"),true);
        vegaEmpty=input(c,"空仓距离 B（m，0%）",s.getString("vega_empty"),true);
        vegaFull=input(c,"满仓距离 A（m，100%）",s.getString("vega_full"),true);
        vegaDistance=check(c,"已明确 PV 为传感器到料面的距离",s.getBoolean("vega_distance"));
        vegaCalibrated=check(c,"启用以上空满距离参考标定",s.getBoolean("vega_calibrated"));
        vegaInvalid=check(c,"模拟 PV 无效状态位0",s.getBoolean("vega_invalid"));
        vegaNonfinite=check(c,"模拟 PV 非有限数 NaN",s.getBoolean("vega_nonfinite"));
        String percent=s.isNull("vega_percent")?"未计算（物理量／单位／标定／状态待核对）":s.getString("vega_percent")+"%（参考计算）";
        c.addView(text("料位："+percent,15,ink));
        button(c,"保存 VEGA 参数",true,v->{try{String result=nativeVegaConfigure(Double.parseDouble(vegaPv.getText().toString()),Integer.parseInt(vegaUnit.getText().toString()),Double.parseDouble(vegaEmpty.getText().toString()),Double.parseDouble(vegaFull.getText().toString()),vegaDistance.isChecked(),vegaCalibrated.isChecked(),vegaInvalid.isChecked(),vegaNonfinite.isChecked());build();show(result);}catch(Exception e){show("请填写有效VEGA参数");}});
        c.addView(text("原厂手册1015559-EN-250117；19064端口为RTU字节隧道，站246。FC04输入2000状态／2002 ABCD值、104单位／106 CDAB值；FC03仅读200及3000。状态和单位的双字高字在前是本模拟配置约定，仍需实机确认。",13,muted));
        c.addView(text("PV可在原厂DTM中选成不同物理量。只在明确为距离、单位可换算且启用有效标定时计算百分比；超出标定区间返回缺项，不推算吨数。该模式独立保存，工艺页实验值保持原配置。",13,muted));
    }
    private void ee600(JSONObject s)throws Exception{
        LinearLayout c=card();heading(c,"EE600 差压仪表参考","空气斜槽子设备 · 独立原厂测量地址配置");
        eePressure=input(c,"差压（Pa，测量上限10000）",s.getString("ee_pressure_pa"),true);
        eeK=input(c,"风机 k 系数（需标定依据）",s.getString("ee_k"),true);
        eeDensity=input(c,"空气密度（kg/m³）",s.getString("ee_density"),true);
        eeCalibrated=check(c,"已配置风量 k 系数与空气密度",s.getBoolean("ee_configured"));
        eeError=check(c,"模拟 EE600 严重测量错误",s.getBoolean("ee_error"));
        button(c,"保存 EE600 参数",true,v->{try{show(nativeEeConfigure(Double.parseDouble(eePressure.getText().toString()),Double.parseDouble(eeK.getText().toString()),Double.parseDouble(eeDensity.getText().toString()),eeCalibrated.isChecked(),eeError.isChecked()));}catch(Exception e){show("请填写有效EE600参数");}});
        c.addView(text("原厂参考：BA_EE600 v1.13，2026-07。RTU原始字节经19063端口传输，站号43，FC03/04，CDAB。未配置风量时1055寄存器不开放；这是本模拟器的缺参限制。差压取压口、风机标定和状态地址仍待确认，数值不能直接作为现场有效点。",13,muted));
        c.addView(text("独立参数保存，不改变工艺页实验值。严重错误输出NaN／0x8000；输入超过10000 Pa的读数钳位到10000 Pa。写指令拒绝。",13,muted));
    }
    private String addresses(){try{StringBuilder b=new StringBuilder();for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:Collections.list(ni.getInetAddresses()))if(!a.isLoopbackAddress()&&a.getHostAddress().indexOf(':')<0)b.append(ni.getName()).append("  ").append(a.getHostAddress()).append('\n');return b.length()>0?b.toString():"当前没有 IPv4 网络地址";}catch(Exception e){return "网络地址暂不可用";}}
    private void connection(){
        LinearLayout c=card();heading(c,"连接网关","应用安装后可离线使用；联调时再启用设备服务。");c.addView(text(addresses(),14,ink));
        button(c,"启动局域网设备服务",true,v->start(true));button(c,"启动 USB／仅本机服务",false,v->start(false));button(c,"停止服务（模拟断连）",false,v->{startService(new Intent(this,DeviceService.class).setAction("STOP"));show("服务已停止，待发队列保留");});
        c.addView(text("扫码 19061：ASCII CR/LF，ACK/NAK\n汽车衡 19062：Toledo Continuous，18字节\n筒仓 1502／拆箱房 1503／空气斜槽 1504\nModbus TCP：站号1，FC03，只读\nEE600 19063：RTU字节隧道，站号43，FC03/04，只读\nVEGA 19064：RTU字节隧道，站246，FC04测量／FC03配置，只读",13,muted));
        LinearLayout d=card();heading(d,"验证与原始报文","手机队列已发送，不等于底座入库或 WMS 已收货。");
        button(d,"运行 C 核心自检",false,v->{int n=nativeSelfTest();show(n==0?"C 核心自检通过：帧格式、校验和、只读协议、队列":"C 核心自检失败，编号 "+n);});
        button(d,"查看原始收发记录",false,v->{TextView t=text(nativeLog(),12,ink);t.setTypeface(Typeface.MONOSPACE);t.setTextIsSelectable(true);ScrollView sv=new ScrollView(this);sv.addView(t);new AlertDialog.Builder(this).setTitle("原始协议记录（最近60KB）").setView(sv).setPositiveButton("关闭",null).setNeutralButton("复制",(a,b)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("raw-frames",nativeLog()));}).show();});
        d.addView(text("首次为个人调试签名 APK。Android 8.0及以上；C核心同时提供32位、64位ARM。无账号密码，无库存直写功能。",13,muted));
    }
    private void start(boolean lan){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
        Intent i=new Intent(this,DeviceService.class).putExtra("lan",lan);startForegroundService(i);show("正在启动服务，请查看顶部状态");
    }
    private void apply(){try{
        JSONObject s=new JSONObject(nativeSnapshot());int f=s.getInt("flags");
        if(page==1){f=(f&~33)|(stable.isChecked()?1:0)|(fault.isChecked()?32:0);}
        else f=(f&~30)|(occupied.isChecked()?2:0)|(person.isChecked()?4:0)|(blockage.isChecked()?8:0)|(leakage.isChecked()?16:0);
        show(nativeConfigure(page==1?Double.parseDouble(gross.getText().toString()):s.getDouble("gross"),page==1?Double.parseDouble(tare.getText().toString()):s.getDouble("tare"),page==2?Double.parseDouble(level.getText().toString()):s.getDouble("level"),page==2?Double.parseDouble(pressure.getText().toString()):s.getDouble("pressure"),page==2?Double.parseDouble(flow.getText().toString()):s.getDouble("flow"),f));
    }catch(Exception e){show("请填写有效数值");}}
    private void show(String s){if(message!=null)message.setText(s);Toast.makeText(this,s,Toast.LENGTH_SHORT).show();updateLive();}
    private void updateText(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void updateLive(){if(live==null)return;try{JSONObject s=new JSONObject(nativeSnapshot());updateText(live,(s.getBoolean("running")?"● 设备服务运行中":"○ 设备服务已停止")+"    待发扫码 "+s.getInt("queued")+"\n净重 "+s.getString("net")+" kg    筒仓料位 "+s.getString("level")+"%");
        if(page==0&&scanMode==1&&referenceLive!=null){String[] phases={"等待发送","准备发送","等待ACK/NAK","ACK已确认","NAK达到上限，已丢弃","等待超时，已丢弃","连接断开，已丢弃","服务停止，已丢弃"};int p=s.getInt("sr_phase");updateText(referenceLive,(s.getBoolean("sr_accepting")?"参考连接就绪":"参考连接未建立／已结束")+"\n"+(p<phases.length?phases[p]:"未知状态")+" · 已发送 "+s.getInt("sr_attempts")+" / 3 次");}
    }catch(Exception ignored){}}
}
