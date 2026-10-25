package com.example.offlinenative;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

    static {
        System.loadLibrary("offlinetest");
    }

    public native String getNativeString();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tv = findViewById(R.id.text_native_result);
        String nativeVal = getNativeString();
        tv.setText("JNI Result: " + nativeVal);
    }
}
