package org.labday.app;
import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
public class Ui {
    public static int dp(android.content.Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static LinearLayout screen(Activity a,String title){
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(245,248,252));
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(a,16);box.setPadding(pad,pad,pad,pad);scroll.addView(box);a.setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        text(box,title,25);return box;
    }
    public static TextView text(LinearLayout box,String value,int size){
        TextView t=new TextView(box.getContext());t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(25,42,62));
        t.setPadding(0,dp(box.getContext(),6),0,dp(box.getContext(),6));box.addView(t);return t;
    }
    public static LinearLayout card(LinearLayout box){
        LinearLayout card=new LinearLayout(box.getContext());card.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(box.getContext(),12);card.setPadding(pad,pad,pad,pad);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(box.getContext(),12));card.setBackground(bg);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(box.getContext(),8),0,0);box.addView(card,params);return card;
    }
    public static Button button(LinearLayout box,String title,Runnable action){
        Button button=new Button(box.getContext());button.setText(title);button.setAllCaps(false);box.addView(button);
        button.setOnClickListener(v->action.run());return button;
    }
    public static EditText field(LinearLayout box,String label,String value,boolean multi){
        text(box,label,14);EditText e=new EditText(box.getContext());e.setText(value);e.setTextSize(16);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(multi?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        e.setSingleLine(!multi);if(multi){e.setMinLines(2);e.setGravity(Gravity.TOP);}
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);box.addView(e);return e;
    }
    public static void toast(android.content.Context c,String value){Toast.makeText(c,value,Toast.LENGTH_LONG).show();}
}
