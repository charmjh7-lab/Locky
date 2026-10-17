package com.locky.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.*;
import java.util.*;

/** One retained native hierarchy. Binding is synchronous and always precedes exposure. */
final class AppGateView extends FrameLayout {
    interface Actions {
        void choose(long token,String pkg,String id,String label,int minutes);
        void extend(long token,String pkg,int minutes);
        void stop(long token,String pkg);
        void edit(long token,String pkg);
    }
    final Actions actions;
    final LinearLayout page,body,footer,duration,purposes,budget,note,block;
    final TextView heading,appName,question,daily,original,available,focusHours,durationTitle;
    final TextView badge;
    final View headerLine;
    final Button direct,edit,extend,stop;
    final ScrollView presetScroll,bodyScroll;
    final GridLayout grid;
    final List<Button> presetButtons=new ArrayList<>(),minuteButtons=new ArrayList<>();
    final FrameLayout editor;
    final LinearLayout editorPanel;
    final EditText input;
    final Button save,cancel;
    private long token;
    private String pkg="",mode="";
    private int minutes=5;
    private boolean active,submitting;
    private String boundData="";
    private int bodyViewport;
    private final Map<TextView,Boolean> labels=new HashMap<>();
    private final Map<TextView,Integer> sizes=new HashMap<>();

    AppGateView(Context context,Actions actions){
        super(context);this.actions=actions;
        setFocusableInTouchMode(true);
        page=column();page.setPadding(dp(20),dp(8),dp(20),dp(12));
        addView(page,new FrameLayout.LayoutParams(-1,-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        LinearLayout top=row();heading=text("앱 시작",18,true,false);badge=text("집중 시간",12,false,true);
        top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));top.addView(badge);top.setMinimumHeight(dp(52));
        page.addView(top);headerLine=new View(context);page.addView(headerLine,new LinearLayout.LayoutParams(-1,dp(1)));gap(page,16);
        // Body can shrink on short/large-font screens; presets retain their own scroll region.
        bodyScroll=new ScrollView(context){@Override protected void onMeasure(int width,int height){bodyViewport=MeasureSpec.getSize(height);super.onMeasure(width,height);}};
        bodyScroll.setClipToPadding(false);
        body=new LinearLayout(context){@Override protected void onMeasure(int width,int height){super.onMeasure(width,MeasureSpec.makeMeasureSpec(Math.max(bodyViewport,minimumBodyHeight(MeasureSpec.getSize(width))),MeasureSpec.EXACTLY));}};
        body.setOrientation(LinearLayout.VERTICAL);bodyScroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        page.addView(bodyScroll,new LinearLayout.LayoutParams(-1,0,1));
        appName=text("",32,true,false);body.addView(appName);gap(body,12);
        question=text("",16,false,true);body.addView(question);gap(body,12);
        budget=row();TextView remaining=text("오늘 남은 시간",13,false,true);daily=text("",24,true,false);
        budget.addView(remaining,new LinearLayout.LayoutParams(0,-2,1));budget.addView(daily);budget.setPadding(0,dp(14),0,dp(8));body.addView(budget);
        note=column();note.setPadding(dp(18),dp(18),dp(18),dp(18));note.addView(text("처음 정한 목적",13,false,true));
        original=text("",20,true,false);note.addView(original);body.addView(note);gap(body,12);
        duration=column();LinearLayout durationHead=row();durationTitle=text("사용할 시간",17,true,false);
        durationHead.addView(durationTitle,new LinearLayout.LayoutParams(0,-2,1));durationHead.addView(text("분",13,false,true));durationHead.setMinimumHeight(dp(32));duration.addView(durationHead);
        LinearLayout choices=row();for(int m=5;m<=10;m++){
            Button b=button(String.valueOf(m),20);b.setPadding(dp(2),dp(6),dp(2),dp(6));b.setMinimumHeight(dp(52));
            final int value=m;b.setOnClickListener(v->{if(active&&!submitting){minutes=value;paintMinutes();}});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(m>5)lp.leftMargin=dp(6);choices.addView(b,lp);minuteButtons.add(b);
        }duration.addView(choices);body.addView(duration);
        purposes=column();LinearLayout purposeHead=row();purposeHead.addView(text("사용 목적",18,true,false),new LinearLayout.LayoutParams(0,-2,1));
        edit=button("편집",13);edit.setContentDescription("앱 사용 목적 편집");purposeHead.addView(edit);purposes.addView(purposeHead);gap(purposes,6);
        direct=button("＋ 직접 적기",15);direct.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);purposes.addView(direct);gap(purposes,6);
        presetScroll=new ScrollView(context);presetScroll.setMinimumHeight(dp(52));grid=new GridLayout(context);grid.setColumnCount(2);presetScroll.addView(grid,new ScrollView.LayoutParams(-1,-2));
        purposes.addView(presetScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout.LayoutParams purposesParams=new LinearLayout.LayoutParams(-1,0,1);purposesParams.topMargin=dp(20);body.addView(purposes,purposesParams);
        block=column();block.setPadding(0,dp(24),0,dp(24));block.addView(text("다시 사용할 수 있는 시간",14,false,true));
        LinearLayout availableRow=row();available=text("",48,true,false);availableRow.addView(available);TextView freeLabel=text("자유 시간 시작",14,false,true);LinearLayout.LayoutParams freeParams=new LinearLayout.LayoutParams(-2,-2);freeParams.leftMargin=dp(12);availableRow.addView(freeLabel,freeParams);block.addView(availableRow);focusHours=text("",13,false,true);block.addView(focusHours);body.addView(block);
        footer=column();footer.setPadding(0,dp(18),0,0);extend=button("5분 연장 →",15);extend.setMinimumHeight(dp(54));footer.addView(extend);gap(footer,12);
        stop=button("그냥 끄기",18);stop.setContentDescription("그냥 끄기");
        android.graphics.drawable.Drawable power=new android.graphics.drawable.InsetDrawable(context.getDrawable(R.drawable.gate_power),0,0,dp(10),0);power.setBounds(0,0,dp(32),dp(22));
        android.text.SpannableString stopLabel=new android.text.SpannableString(" 그냥 끄기");stopLabel.setSpan(new android.text.style.ImageSpan(power,android.text.style.ImageSpan.ALIGN_BOTTOM),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);stop.setText(stopLabel);
        stop.setMinimumHeight(dp(58));footer.addView(stop);page.addView(footer);

        // Native sheet inside the same accessibility window: no dialog window/token race.
        editor=new FrameLayout(context);editor.setBackgroundColor(0x8011251E);editor.setClickable(true);editor.setVisibility(GONE);addView(editor,new FrameLayout.LayoutParams(-1,-1));
        editorPanel=column();editorPanel.setPadding(dp(20),dp(12),dp(20),dp(20));editor.addView(editorPanel,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        LinearLayout editorHead=row();editorHead.addView(text("이번에 할 일",20,true,false),new LinearLayout.LayoutParams(0,-2,1));cancel=button("닫기",14);editorHead.addView(cancel);editorPanel.addView(editorHead);
        input=new EditText(context);input.setSingleLine(true);input.setTextSize(16);sizes.put(input,16);input.setHint("예: 친구에게 답장");input.setContentDescription("목적");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);editorPanel.addView(input,new LinearLayout.LayoutParams(-1,dp(56)));
        gap(editorPanel,12);save=button("저장하고 시작 ✓",15);editorPanel.addView(save);
        direct.setOnClickListener(v->openEditor());cancel.setOnClickListener(v->closeEditor());
        save.setOnClickListener(v->submitDirect());input.setOnEditorActionListener((v,action,event)->{submitDirect();return true;});
        edit.setOnClickListener(v->{if(claim())actions.edit(token,pkg);});
        extend.setOnClickListener(v->{if(claim())actions.extend(token,pkg,minutes);});
        stop.setOnClickListener(v->{if(claim())actions.stop(token,pkg);});
        ViewCompat.setOnApplyWindowInsetsListener(this,(v,insets)->{
            var bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            var ime=insets.getInsets(WindowInsetsCompat.Type.ime());
            setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return WindowInsetsCompat.CONSUMED;
        });
        applyTheme();
    }
    void bind(long token,String mode,String pkg,JSONObject data) throws JSONException {
        boolean changed=this.token!=token||!this.mode.equals(mode)||!this.pkg.equals(pkg);String fingerprint=data.toString();if(!changed&&fingerprint.equals(boundData))return;
        boundData=fingerprint;this.token=token;this.pkg=pkg;this.mode=mode;
        if(changed){closeEditor();minutes=5;submitting=false;presetScroll.scrollTo(0,0);bodyScroll.scrollTo(0,0);}
        JSONObject g=data.getJSONObject("gate"),prefs=data.getJSONObject("prefs");boolean blocked=mode.equals("blocked"),renew=mode.equals("renew");
        heading.setText(blocked?"사용 제한":renew?"사용 연장":"앱 시작");appName.setText(g.optString("label","앱 사용"));badge.setText(data.optBoolean("free")?"자유 시간":"집중 시간");
        JSONArray rules=data.getJSONArray("rules");boolean policyBlock=rules.length()>0&&"locked".equals(rules.getJSONObject(0).optString("mode"));
        question.setText(blocked?(policyBlock?"집중 시간에는 차단된 앱이에요":"오늘의 사용 시간을 모두 썼어요"):renew?"같은 목적으로 더 사용하나요?":"무엇을 하러 들어가나요?");
        daily.setText(format(g.optLong("daily")));budget.setVisibility(blocked?GONE:VISIBLE);duration.setVisibility(blocked?GONE:VISIBLE);
        note.setVisibility(renew?VISIBLE:GONE);original.setText(g.optString("purpose").isEmpty()?"선택한 목적 없음":g.optString("purpose"));
        durationTitle.setText(renew?"추가할 시간":"사용할 시간");purposes.setVisibility(!blocked&&!renew?VISIBLE:GONE);extend.setVisibility(renew?VISIBLE:GONE);
        // The spacer belongs to the extension button, not the independent red exit.
        footer.getChildAt(1).setVisibility(renew?VISIBLE:GONE);block.setVisibility(blocked?VISIBLE:GONE);
        available.setText(time(prefs.optInt("end")));focusHours.setText(time(prefs.optInt("start"))+"–"+time(prefs.optInt("end"))+" 집중 시간");
        JSONArray ps=data.getJSONArray("purposes");
        for(int i=0;i<ps.length();i++){
            Button b;if(i<presetButtons.size())b=presetButtons.get(i);else{
                b=button("",15);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setMaxLines(2);b.setEllipsize(TextUtils.TruncateAt.END);b.setPadding(dp(12),dp(10),dp(12),dp(10));
                GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=GridLayout.LayoutParams.WRAP_CONTENT;lp.columnSpec=GridLayout.spec(i%2,1f);lp.rowSpec=GridLayout.spec(i/2);lp.setMargins(i%2==0?0:dp(3),0,i%2==0?dp(3):0,dp(6));lp.setGravity(Gravity.FILL);grid.addView(b,lp);presetButtons.add(b);
            }
            JSONObject p=ps.getJSONObject(i);String id=p.getString("id");long boundToken=token;String boundPkg=pkg;
            android.graphics.drawable.Drawable link=null;
            if(!p.optString("pkg").isEmpty()||!p.optString("uri").isEmpty()){link=getContext().getDrawable(R.drawable.gate_link).mutate();link.setBounds(0,0,dp(16),dp(16));}
            b.setCompoundDrawablesRelative(null,null,link,null);b.setCompoundDrawablePadding(dp(6));
            b.setText(p.getString("label"));b.setVisibility(VISIBLE);b.setOnClickListener(v->{if(this.token==boundToken&&claim())actions.choose(boundToken,boundPkg,id,"",minutes);});
        }
        for(int i=ps.length();i<presetButtons.size();i++)presetButtons.get(i).setVisibility(GONE);
        applyTheme();
    }
    void setActive(boolean active){this.active=active;if(!active)closeEditor();setImportantForAccessibility(active?IMPORTANT_FOR_ACCESSIBILITY_AUTO:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);}
    boolean claim(){if(!active||submitting)return false;submitting=true;return true;}
    void retry(){submitting=false;}
    void openEditor(){if(!active||submitting)return;input.setText("");editor.setVisibility(VISIBLE);page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);input.requestFocus();input.post(()->{if(active&&editor.getVisibility()==VISIBLE)((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});}
    void submitDirect(){String label=input.getText().toString().trim();if(label.isEmpty()){input.setError("목적을 1~100자로 입력해 주세요.");return;}if(claim())actions.choose(token,pkg,"",label,minutes);}
    void closeEditor(){if(editor.getVisibility()==VISIBLE){((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);editor.setVisibility(GONE);input.clearFocus();page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)closeEditor();return true;}return super.dispatchKeyEvent(event);}
    void close(){setActive(false);}
    void applyTheme(){
        Context c=getContext();boolean dark=UiTheme.dark(c);setBackgroundColor(UiTheme.background(c));editorPanel.setBackgroundColor(UiTheme.background(c));
        headerLine.setBackgroundColor(Color.parseColor(dark?"#35483C":"#D8DFD8"));
        for(var entry:sizes.entrySet())entry.getKey().setTextSize(entry.getValue());
        setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        for(var entry:labels.entrySet())entry.getKey().setTextColor(entry.getValue()?UiTheme.muted(c):UiTheme.ink(c));
        input.setTextColor(UiTheme.ink(c));input.setHintTextColor(UiTheme.muted(c));input.setBackgroundTintList(ColorStateList.valueOf(UiTheme.accent(c)));
        note.setBackground(shape(Color.parseColor(dark?"#253D30":"#E5EEE7"),0,0));
        for(Button b:presetButtons){paint(b,UiTheme.surface(c),UiTheme.ink(c),8,false);android.graphics.drawable.Drawable link=b.getCompoundDrawablesRelative()[2];if(link!=null)link.setTint(UiTheme.ink(c));}
        paint(direct,Color.parseColor(dark?"#253D30":"#E5EEE7"),UiTheme.accent(c),8,false);
        paint(edit,Color.TRANSPARENT,UiTheme.accent(c),8,false);paint(cancel,Color.TRANSPARENT,UiTheme.accent(c),8,false);
        paint(save,UiTheme.accent(c),dark?Color.parseColor("#10291C"):Color.WHITE,10,false);
        paint(extend,UiTheme.accent(c),dark?Color.parseColor("#10291C"):Color.WHITE,10,false);
        paint(stop,Color.parseColor(dark?"#BE3647":"#B72F3D"),Color.WHITE,12,false);
        paintMinutes();
    }
    private void paintMinutes(){Context c=getContext();for(int i=0;i<minuteButtons.size();i++){Button b=minuteButtons.get(i);boolean selected=minutes==i+5;b.setSelected(selected);b.setContentDescription((i+5)+"분"+(selected?", 선택됨":""));paint(b,selected?UiTheme.accent(c):UiTheme.surface(c),selected?(UiTheme.dark(c)?Color.parseColor("#10291C"):Color.WHITE):UiTheme.ink(c),9,!selected);}extend.setText(minutes+"분 연장 →");}
    private void paint(Button b,int bg,int ink,int radius,boolean border){b.setTextColor(ink);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22637069),shape(bg,radius,border?1:0),null));}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),Color.parseColor(UiTheme.dark(getContext())?"#35483C":"#D8DFD8"));return d;}
    private TextView text(String value,int size,boolean bold,boolean muted){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);sizes.put(t,size);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));labels.put(t,muted);return t;}
    private Button button(String label,int size){Button b=new Button(getContext());b.setText(label);b.setTextSize(size);sizes.put(b,size);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setStateListAnimator(null);return b;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void gap(LinearLayout parent,int height){parent.addView(new View(getContext()),new LinearLayout.LayoutParams(1,dp(height)));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private int minimumBodyHeight(int width){
        int total=0;
        for(int i=0;i<body.getChildCount();i++){
            View child=body.getChildAt(i);if(child.getVisibility()==GONE)continue;
            LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)child.getLayoutParams();
            int childWidth=Math.max(0,width-lp.leftMargin-lp.rightMargin),height=0;
            if(child==purposes){
                for(int j=0;j<purposes.getChildCount();j++){
                    View item=purposes.getChildAt(j);
                    if(item==presetScroll){height+=presetScroll.getMinimumHeight();continue;}
                    int fixed=item.getLayoutParams().height;item.measure(MeasureSpec.makeMeasureSpec(childWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,fixed),fixed>=0?MeasureSpec.EXACTLY:MeasureSpec.UNSPECIFIED));height+=item.getMeasuredHeight();
                }
            }else {child.measure(MeasureSpec.makeMeasureSpec(childWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,lp.height),lp.height>=0?MeasureSpec.EXACTLY:MeasureSpec.UNSPECIFIED));height=child.getMeasuredHeight();}
            total+=height+lp.topMargin+lp.bottomMargin;
        }
        return total;
    }
    static String format(long ms){long s=(Math.max(0,ms)+999)/1000;return String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);}
    private static String time(int m){return String.format(Locale.ROOT,"%02d:%02d",m/60,m%60);}
    @Override protected void onMeasure(int width,int height){
        int availableWidth=MeasureSpec.getSize(width)-getPaddingLeft()-getPaddingRight();
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)page.getLayoutParams();int target=Math.min(availableWidth,dp(740));if(lp.width!=target)lp.width=target;
        super.onMeasure(width,height);
    }
}
