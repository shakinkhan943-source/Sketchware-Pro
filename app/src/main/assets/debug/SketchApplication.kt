package <?package_name?>;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

class SketchApplication : Application() {

    companion object {
        private var mApplicationContext: Context? = null

        @JvmStatic
        fun getContext(): Context? {
            return mApplicationContext
        }
    }

    override fun onCreate() {
        mApplicationContext = applicationContext

        Thread.setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler { _, throwable ->
            val intent = Intent(applicationContext, DebugActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.putExtra("error", Log.getStackTraceString(throwable))
            startActivity(intent)
            Process.killProcess(Process.myPid())
            System.exit(1)
        })
        super.onCreate()
    }
}
