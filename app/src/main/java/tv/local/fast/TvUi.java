package tv.local.fast;

import android.app.*;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;

final class TvUi {
    static final int BG=0xff111916,SURFACE=0xff1c2922,INK=0xffedf4ef,MUTED=0xffb5c7bb,ACCENT=0xffb8dcc8;
    final Activity activity;
    TvUi(Activity activity){this.activity=activity;}
    int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    TextView text(String value,int size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    GradientDrawable shape(int color,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(5));if(stroke!=0)g.setStroke(dp(2),stroke);return g;}
    /** Focus background for a focusable widget such as a button. */
    StateListDrawable focus(){
        StateListDrawable bg=new StateListDrawable();bg.addState(new int[]{android.R.attr.state_focused},shape(0xff304d3c,ACCENT));
        bg.addState(new int[]{android.R.attr.state_pressed},shape(0xff304d3c,ACCENT));bg.addState(new int[]{},shape(SURFACE,0));return bg;
    }
    /** Selector an AbsListView draws behind the current row or cell; its own focus state drives it. */
    StateListDrawable rowSelector(){
        StateListDrawable selector=new StateListDrawable();selector.addState(new int[]{android.R.attr.state_focused},shape(0xff304d3c,ACCENT));
        selector.addState(new int[]{},shape(0xff23372b,0));return selector;
    }
    Button button(String text,Runnable click){
        Button b=new Button(activity);b.setText(text);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(INK);b.setMinHeight(dp(48));
        b.setBackground(focus());b.setPadding(dp(12),0,dp(12),0);b.setOnClickListener(v->click.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(48));p.setMarginEnd(dp(8));b.setLayoutParams(p);return b;
    }
    void list(ListView list){
        list.setDivider(new ColorDrawable(BG));list.setDividerHeight(dp(5));list.setCacheColorHint(BG);list.setItemsCanFocus(false);
        list.setSelector(rowSelector());
    }
    LinearLayout header(String active,Runnable live,Runnable films,Runnable series){
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark=new ImageView(activity);mark.setImageResource(R.drawable.icon);LinearLayout.LayoutParams logo=new LinearLayout.LayoutParams(dp(52),dp(52));logo.setMarginEnd(dp(10));top.addView(mark,logo);
        TextView title=text(activity.getString(R.string.app_name),22,INK);title.setTypeface(null,Typeface.BOLD);top.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        String[] names={"Live TV","Films","Series"};Runnable[] actions={live,films,series};
        for(int i=0;i<names.length;i++){Button b=button(names[i],actions[i]);b.setTag("nav-"+names[i]);if(active.equals(names[i])){b.setBackground(shape(ACCENT,0));b.setTextColor(BG);b.setEnabled(false);}top.addView(b);}return top;
    }
    static void searchDialog(Activity activity,View root,String title,String hint,String initial,java.util.function.Consumer<String> apply){
        EditText field=new EditText(activity);field.setSingleLine(true);field.setText(initial);field.setHint(hint);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(field).setPositiveButton("Search",null).setNeutralButton("Clear",null).setNegativeButton("Cancel",null).create();
        java.util.function.Consumer<String> finish=value->{
            if(!dialog.isShowing())return;hideKeyboard(activity,field);dialog.dismiss();
            root.post(()->{if(activity.isDestroyed()||activity.isFinishing())return;if(Build.VERSION.SDK_INT>=30&&root.getWindowInsetsController()!=null)root.getWindowInsetsController().hide(WindowInsets.Type.ime());apply.accept(value);});
        };
        Runnable submit=()->finish.accept(field.getText().toString().trim());
        field.setOnEditorActionListener((view,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH||action==EditorInfo.IME_ACTION_DONE||action==EditorInfo.IME_ACTION_GO||action==EditorInfo.IME_ACTION_SEND||(action==EditorInfo.IME_NULL&&event==null)){submit.run();return true;}return false;
        });
        field.setOnKeyListener((view,key,event)->{if(key!=KeyEvent.KEYCODE_ENTER&&key!=KeyEvent.KEYCODE_NUMPAD_ENTER)return false;if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())submit.run();return true;});
        dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->submit.run());dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->finish.accept(""));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{hideKeyboard(activity,field);dialog.dismiss();});field.requestFocus();field.setSelection(field.length());});dialog.show();
    }
    private static void hideKeyboard(Activity activity,EditText field){
        InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(field.getWindowToken(),0);
        if(Build.VERSION.SDK_INT>=30&&field.getWindowInsetsController()!=null)field.getWindowInsetsController().hide(WindowInsets.Type.ime());field.clearFocus();field.setFocusable(false);
    }
}
