package ru.spgsroot.peachy.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import ru.spgsroot.peachy.R

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forwarded = ShareIntents.forward(this, intent)
        if (forwarded != null) startActivity(forwarded)
        else if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
