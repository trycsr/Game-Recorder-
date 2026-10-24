package com.sec.game.gamecast.floatingui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible screen that shows Android's own "start recording?" dialog. */
public class OfflineProjectionActivity extends Activity {
    private static final int REQUEST = 4711;
    private boolean mAsked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mAsked = savedInstanceState.getBoolean("asked", false);
        }
        if (!mAsked) {
            mAsked = true;
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST);
            } catch (Throwable t) {
                OfflineRuntime.onProjectionResult(this, RESULT_CANCELED, null);
                finish();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("asked", mAsked);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST) {
            OfflineRuntime.onProjectionResult(this, resultCode, data);
            finish();
        }
    }
}
