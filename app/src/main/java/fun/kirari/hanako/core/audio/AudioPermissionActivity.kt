package `fun`.kirari.hanako.core.audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 只做一件事：申请录音权限，然后把结果回给调用方。
 *
 * 悬浮球是服务，不能在服务里弹运行时权限框，所以需要一个透明 Activity 中转。
 * 界面上什么都没有（清单里配了半透明主题），申请完立刻 finish。
 */
class AudioPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            finishWith(true)
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        finishWith(granted)
    }

    private fun finishWith(granted: Boolean) {
        setResult(if (granted) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 0x4A21

        fun hasPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun intent(context: Context): Intent {
            return Intent(context, AudioPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
