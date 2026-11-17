package com.owo233.tcqt.features.advanced

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.owo233.tcqt.annotations.RegisterAction
import com.owo233.tcqt.api.Feature
import com.owo233.tcqt.core.action.ActionProcess
import com.owo233.tcqt.core.env.HookEnv
import com.owo233.tcqt.core.env.Toasts
import com.owo233.tcqt.core.env.isFlagEnabled
import com.owo233.tcqt.core.env.load
import com.owo233.tcqt.core.env.loadOrThrow
import com.owo233.tcqt.core.hook.hookAfter
import com.owo233.tcqt.core.hook.hookBefore
import com.owo233.tcqt.core.hook.hookMethodAfter
import com.owo233.tcqt.core.log.Log
import com.owo233.tcqt.core.reflect.callMethod
import com.owo233.tcqt.core.reflect.callStaticMethod
import com.owo233.tcqt.core.reflect.getObjectOrNull
import com.owo233.tcqt.core.sync.ModuleScope
import com.owo233.tcqt.core.sync.ReceiverRegistry
import com.owo233.tcqt.host.service.SmsCodeBridge
import com.owo233.tcqt.host.service.milky.MilkyServer
import com.tencent.mobileqq.app.QBaseActivity
import com.tencent.qphone.base.util.BaseApplication
import com.tencent.qqnt.kernel.api.ILoginService
import com.tencent.qqnt.kernel.nativeinterface.AccountType
import com.tencent.qqnt.kernel.nativeinterface.AppInfo
import com.tencent.qqnt.kernel.nativeinterface.ILoginCallback
import com.tencent.qqnt.kernel.nativeinterface.LoginProcessReqBody
import com.tencent.qqnt.kernel.nativeinterface.LoginResult
import com.tencent.qqnt.kernel.nativeinterface.PwdLoginInfo
import com.tencent.qqnt.kernel.nativeinterface.UserInfo
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import mqq.app.AppRuntime
import mqq.app.Constants
import mqq.app.MobileQQ
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

@RegisterAction(processes = [ActionProcess.MAIN, ActionProcess.TOOL])
object AutoRelogin : Feature(
    key = "auto_relogin",
    name = "自动重登",
    desc = "被踢下线或会话过期时自动重新登录，支持自动点击人机验证无障碍按钮及短信接码辅助。",
    uiOrder = 16,
) {

    private const val TAG = "AutoRelogin"
    private const val FLAG_AUTO_CAPTCHA_ACCESSIBILITY = 0
    private const val FLAG_AUTO_DISMISS_KICK_DIALOG = 1
    private const val FLAG_AUTO_LOGIN_ON_LOGIN_PAGE = 2
    private const val FLAG_AUTO_SMS_VERIFY = 3

    private const val KICK_DEBOUNCE_MS = 5_000L
    private const val CAPTCHA_CLICK_COOLDOWN_MS = 35_000L
    private const val CAPTCHA_POLL_INTERVAL_MS = 1_200L
    private const val CAPTCHA_WATCH_TIMEOUT_MS = 90_000L
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val CAPTCHA_GEOMETRY_DELAY_MS = 3_500L
    private const val SMS_POLL_INTERVAL_MS = 2_000L
    private const val SMS_POLL_TIMEOUT_MS = 120_000L

    private val KICK_LOGOUT_REASONS = setOf(
        "kicked",
        "expired",
        "forceLogout",
        "secKicked",
    )

    private val KICK_NOTIFICATION_ACTIONS = setOf(
        "mqq.intent.action.ACCOUNT_KICKED",
        "mqq.intent.action.ACCOUNT_EXPIRED",
    )

    private val targetUin by stringOption(
        settingKey = "target_uin",
        name = "目标 QQ 号",
        defaultValue = "",
        desc = "留空则自动重登被踢下线的当前账号",
        placeholder = "留空使用被踢账号",
    )

    private val password by stringOption(
        settingKey = "password",
        name = "登录密码",
        defaultValue = "",
        desc = "被踢清除票据后用于自动发起密码登录",
        placeholder = "请输入 QQ 登录密码",
    )

    private val options by multiIntOption(
        settingKey = "options",
        name = "运行选项",
        defaultValue = 11,
        options = listOf(
            "自动点击人机验证无障碍按钮",
            "自动关闭下线提示弹窗",
            "处于登录页时自动尝试登录",
            "自动处理短信接码验证",
        ),
    )

    private val retryDelaySeconds by sliderOption(
        settingKey = "retry_delay",
        name = "重登延迟",
        defaultValue = 3,
        desc = "检测到下线后等待一段时间再发起重登，避免与宿主清理流程冲突",
        min = 1,
        max = 30,
        step = 1,
        suffix = "秒",
    )

    private val smsFetchUrl by stringOption(
        settingKey = "sms_fetch_url",
        name = "短信取码接口 URL",
        defaultValue = "",
        desc = "可选：GET 请求取码接口（支持 {uin}、{phone} 占位符），留空则使用本机短信授权或 Milky 推送",
        placeholder = "http://127.0.0.1:8080/sms?phone={phone}",
    )

    private val smsCodeRegex by stringOption(
        settingKey = "sms_code_regex",
        name = "短信验证码正则",
        defaultValue = "(?<!\\d)(\\d{6})(?!\\d)",
        desc = "从取码接口返回文本中提取验证码的正则表达式",
        placeholder = "(?<!\\d)(\\d{6})(?!\\d)",
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloginScheduled = AtomicBoolean(false)
    private val reloginInProgress = AtomicBoolean(false)

    @Volatile
    private var lastKickedUin: String = ""

    @Volatile
    private var lastKickTriggeredAt: Long = 0L

    @Volatile
    private var lastLoginAttemptAt: Long = 0L

    @Volatile
    private var consecutiveFailures: Int = 0

    @Volatile
    private var suppressedLoginActivityHash: Int = 0

    @Volatile
    private var loginActivityRef: WeakReference<Activity>? = null

    private val watchedWebViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    )
    private val lastCaptchaClickAt = Collections.synchronizedMap(WeakHashMap<WebView, Long>())
    private val lastSmsActionAt = Collections.synchronizedMap(WeakHashMap<WebView, Long>())
    private val smsPollingActive = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    )
    private val smsBroadcastRegistered = AtomicBoolean(false)

    private val getChildIdMethod: Method? by lazy {
        runCatching {
            AccessibilityNodeInfo::class.java.getMethod(
                "getChildId",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    override fun install() {
        registerSmsBridgeReceiver()
        when (currentProcess) {
            ActionProcess.MAIN -> {
                installKickAndReloginHooks()
                installVerifyWebViewHooks()
            }
            ActionProcess.TOOL -> {
                installVerifyWebViewHooks()
            }
            else -> Unit
        }
    }

    private fun registerSmsBridgeReceiver() {
        if (!smsBroadcastRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(SmsCodeBridge.ACTION_SUBMIT_SMS_CODE)
            if (currentProcess == ActionProcess.MAIN) {
                addAction(SmsCodeBridge.ACTION_SMS_CHALLENGE)
            }
        }
        ReceiverRegistry.register(
            hostApp,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        SmsCodeBridge.ACTION_SUBMIT_SMS_CODE -> {
                            val code = intent.getStringExtra(SmsCodeBridge.EXTRA_CODE).orEmpty()
                            val ts = intent.getLongExtra(
                                SmsCodeBridge.EXTRA_TIMESTAMP,
                                System.currentTimeMillis(),
                            )
                            SmsCodeBridge.submitCode(null, code, timestampMs = ts, broadcast = false)
                        }
                        SmsCodeBridge.ACTION_SMS_CHALLENGE -> {
                            val uin = intent.getStringExtra(SmsCodeBridge.EXTRA_UIN).orEmpty()
                            val phone = intent.getStringExtra(SmsCodeBridge.EXTRA_PHONE).orEmpty()
                            val ts = intent.getLongExtra(
                                SmsCodeBridge.EXTRA_TIMESTAMP,
                                System.currentTimeMillis(),
                            )
                            SmsCodeBridge.updateChallenge(null, uin, phone, ts, broadcast = false)
                            notifyMilkySmsChallenge(uin, phone, ts)
                        }
                    }
                }
            },
            filter,
            exported = false,
        )
    }

    private fun notifyMilkySmsChallenge(uin: String, maskedPhone: String, timestampMs: Long) {
        if (currentProcess != ActionProcess.MAIN) return
        runCatching {
            val payload = JSONObject().apply {
                put("event_type", "tcqt_sms_verify_required")
                put("time", timestampMs / 1000L)
                put("self_id", uin.toLongOrNull() ?: 0L)
                put("data", JSONObject().apply {
                    put("uin", uin)
                    put("masked_phone", maskedPhone)
                    put("timestamp", timestampMs)
                })
            }.toString()
            MilkyServer.broadcastCustomJson(payload)
        }
    }

    private fun installKickAndReloginHooks() {
        runCatching {
            val logoutMethod = AppRuntime::class.java.getDeclaredMethod(
                "logout",
                Constants.LogoutReason::class.java,
                Boolean::class.javaPrimitiveType,
            )
            logoutMethod.hookBefore { param ->
                val reason = param.args.getOrNull(0) as? Constants.LogoutReason ?: return@hookBefore
                if (reason.name !in KICK_LOGOUT_REASONS) return@hookBefore
                val runtime = param.thisObject as? AppRuntime ?: return@hookBefore
                val uin = runtime.currentAccountUin?.takeIf { it.isNotBlank() && it != "0" }
                if (uin != null) {
                    lastKickedUin = uin
                }
                Log.i("$TAG: captured logout reason=${reason.name}, uin=${lastKickedUin}")
            }
            logoutMethod.hookAfter { param ->
                val reason = param.args.getOrNull(0) as? Constants.LogoutReason ?: return@hookAfter
                if (reason.name !in KICK_LOGOUT_REASONS) return@hookAfter
                onKickDetected("AppRuntime.logout(${reason.name})")
            }
        }.onFailure {
            Log.e("$TAG: hook AppRuntime.logout failed", it)
        }

        runCatching {
            val ntKickClass = load("com.tencent.mobileqq.kick.NTKickProcessor") ?: return@runCatching
            val kickedInfoClass = load("com.tencent.qqnt.kernel.nativeinterface.KickedInfo") ?: return@runCatching
            val method = ntKickClass.declaredMethods.firstOrNull { m ->
                val pts = m.parameterTypes
                pts.size == 2 && AppRuntime::class.java.isAssignableFrom(pts[0]) && pts[1] == kickedInfoClass
            } ?: return@runCatching
            method.hookBefore { param ->
                val runtime = param.args.getOrNull(0) as? AppRuntime ?: return@hookBefore
                val uin = runtime.currentAccountUin?.takeIf { it.isNotBlank() && it != "0" }
                if (uin != null) {
                    lastKickedUin = uin
                }
            }
            method.hookAfter {
                onKickDetected("NTKickProcessor")
            }
        }.onFailure {
            Log.w("$TAG: hook NTKickProcessor skipped: ${it.message}")
        }

        runCatching {
            val notifClass = load("com.tencent.mobileqq.activity.NotificationActivity") ?: return@runCatching
            val onCreate = findLifecycleMethod(notifClass, "doOnCreate", "onCreate")
            onCreate?.hookAfter { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfter
                handleNotificationActivity(activity)
            }
            val onNewIntent = findLifecycleMethod(notifClass, "doOnNewIntent", "onNewIntent")
            onNewIntent?.hookAfter { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfter
                val newIntent = param.args.getOrNull(0) as? Intent
                if (newIntent != null) activity.intent = newIntent
                handleNotificationActivity(activity)
            }
        }.onFailure {
            Log.w("$TAG: hook NotificationActivity skipped: ${it.message}")
        }

        runCatching {
            val loginActClass = load("com.tencent.mobileqq.activity.LoginActivity") ?: return@runCatching
            val doOnResume = findLifecycleMethod(loginActClass, "doOnResume", "onResume")
            doOnResume?.hookBefore { param ->
                val activity = param.thisObject as? Activity ?: return@hookBefore
                loginActivityRef = WeakReference(activity)
                val intent = activity.intent
                if (intent?.action == "mqq.intent.action.KICK_TO_LOGIN" &&
                    (options.isFlagEnabled(FLAG_AUTO_DISMISS_KICK_DIALOG) || reloginScheduled.get() || reloginInProgress.get())
                ) {
                    val uin = intent.getStringExtra("uin")
                    if (!uin.isNullOrBlank()) {
                        lastKickedUin = uin
                    }
                    intent.action = ""
                    Log.i("$TAG: suppressed KICK_TO_LOGIN route to NotificationActivity")
                }
            }
            doOnResume?.hookAfter { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfter
                loginActivityRef = WeakReference(activity)
                if (!options.isFlagEnabled(FLAG_AUTO_LOGIN_ON_LOGIN_PAGE)) return@hookAfter
                if (suppressedLoginActivityHash == System.identityHashCode(activity)) return@hookAfter
                val runtime = runCatching { MobileQQ.getMobileQQ().peekAppRuntime() }.getOrNull()
                if (runtime?.isLogin == true) return@hookAfter
                val now = SystemClock.elapsedRealtime()
                if (now - lastLoginAttemptAt < 30_000L) return@hookAfter
                scheduleRelogin(delayMs = retryDelaySeconds * 1000L, reason = "LoginActivity.onResume")
            }
        }.onFailure {
            Log.w("$TAG: hook LoginActivity skipped: ${it.message}")
        }
    }

    private fun handleNotificationActivity(activity: Activity) {
        val action = activity.intent?.action ?: return
        if (action !in KICK_NOTIFICATION_ACTIONS) return
        val uin = activity.intent?.getStringExtra("uin")
        if (!uin.isNullOrBlank()) {
            lastKickedUin = uin
        }
        onKickDetected("NotificationActivity($action)")
        if (options.isFlagEnabled(FLAG_AUTO_DISMISS_KICK_DIALOG) && !activity.isFinishing) {
            Log.i("$TAG: finishing kick NotificationActivity")
            activity.finish()
        }
    }

    private fun onKickDetected(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKickTriggeredAt < KICK_DEBOUNCE_MS) {
            Log.d("$TAG: debounced duplicate kick from $source")
            return
        }
        lastKickTriggeredAt = now
        consecutiveFailures = 0
        suppressedLoginActivityHash = 0
        Log.i("$TAG: kick detected via $source, targetUin=${resolveTargetUin()}")
        scheduleRelogin(delayMs = retryDelaySeconds * 1000L, reason = source)
    }

    private fun scheduleRelogin(delayMs: Long, reason: String) {
        if (reloginInProgress.get()) {
            Log.d("$TAG: relogin already in progress, skip $reason")
            return
        }
        if (!reloginScheduled.compareAndSet(false, true)) {
            Log.d("$TAG: relogin already scheduled, skip $reason")
            return
        }
        Log.i("$TAG: scheduled relogin in ${delayMs}ms (reason=$reason)")
        mainHandler.postDelayed({
            reloginScheduled.set(false)
            attemptRelogin()
        }, delayMs)
    }

    private fun resolveTargetUin(): String {
        return targetUin.trim().ifEmpty { lastKickedUin.trim() }
    }

    private fun attemptRelogin() {
        val runtime = runCatching { MobileQQ.getMobileQQ().peekAppRuntime() }.getOrNull()
        if (runtime?.isLogin == true) {
            Log.i("$TAG: already logged in, abort relogin")
            consecutiveFailures = 0
            return
        }

        val uin = resolveTargetUin()
        val pwd = password
        if (uin.isEmpty() || pwd.isEmpty()) {
            Log.w("$TAG: missing targetUin or password, cannot auto-relogin")
            Toasts.error("自动重登失败：请先在设置中填写密码")
            return
        }

        if (!reloginInProgress.compareAndSet(false, true)) return
        lastLoginAttemptAt = SystemClock.elapsedRealtime()

        val activity = findAvailableQBaseActivity()
        if (activity != null) {
            executeMainlineLogin(activity, uin, pwd)
            return
        }

        // Bring LoginActivity forward so PuzzleVerifyComponent / NTDevLockVerifyComponent have a FragmentActivity.
        val launched = launchLoginActivity()
        if (launched) {
            waitForActivityAndLogin(uin, pwd, attemptsLeft = 10)
        } else {
            executeHeadlessNtLogin(uin, pwd)
        }
    }

    private fun waitForActivityAndLogin(uin: String, pwd: String, attemptsLeft: Int) {
        mainHandler.postDelayed({
            val runtime = runCatching { MobileQQ.getMobileQQ().peekAppRuntime() }.getOrNull()
            if (runtime?.isLogin == true) {
                reloginInProgress.set(false)
                consecutiveFailures = 0
                return@postDelayed
            }
            val activity = findAvailableQBaseActivity()
            if (activity != null) {
                executeMainlineLogin(activity, uin, pwd)
            } else if (attemptsLeft > 1) {
                waitForActivityAndLogin(uin, pwd, attemptsLeft - 1)
            } else {
                Log.w("$TAG: LoginActivity did not surface in time, falling back to headless ILoginService")
                executeHeadlessNtLogin(uin, pwd)
            }
        }, 350L)
    }

    private fun findAvailableQBaseActivity(): QBaseActivity? {
        val top = runCatching { QBaseActivity.sTopActivity }.getOrNull()
        if (top != null && !top.isFinishing && !top.isDestroyed) return top
        val loginAct = loginActivityRef?.get() as? QBaseActivity
        if (loginAct != null && !loginAct.isFinishing && !loginAct.isDestroyed) return loginAct
        return null
    }

    private fun launchLoginActivity(): Boolean {
        return runCatching {
            val loginClass = load("com.tencent.mobileqq.activity.LoginActivity") ?: return false
            val app = HookEnv.application
            val intent = Intent(app, loginClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            app.startActivity(intent)
            true
        }.onFailure {
            Log.w("$TAG: start LoginActivity failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun executeMainlineLogin(activity: QBaseActivity, uin: String, pwd: String) {
        runCatching {
            val avClass = loadOrThrow("com.tencent.mobileqq.login.av")
            val mainline = avClass.callStaticMethod("b", 1)
                ?: error("av.b(1) returned null")

            val builderClass = loadOrThrow("com.tencent.mobileqq.login.c\$a")
            val builder = builderClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
            ).newInstance(1, 13, uin, pwd)

            val md5Bytes = MessageDigest.getInstance("MD5").digest(pwd.toByteArray(Charsets.UTF_8))
            runCatching { builder.callMethod("A", md5Bytes) }
            runCatching {
                val reqBody = load("com.tencent.mobileqq.util.LoginUtil")?.callStaticMethod("b")
                if (reqBody != null) builder.callMethod("z", reqBody)
            }
            val loginParams = builder.callMethod("q")
                ?: error("c.a.q() returned null")

            val loadingClass = loadOrThrow("com.tencent.mobileqq.login.ah")
            val loadingProxy = Proxy.newProxyInstance(
                HookEnv.hostClassLoader,
                arrayOf(loadingClass),
            ) { proxy, method, args ->
                handleObjectProxyMethod(proxy, method, args)
            }

            val callbackClass = loadOrThrow("com.tencent.mobileqq.login.aj")
            val callbackProxy = Proxy.newProxyInstance(
                HookEnv.hostClassLoader,
                arrayOf(callbackClass),
            ) { proxy, method, args ->
                if (method.declaringClass == Any::class.java) {
                    return@newProxyInstance handleObjectProxyMethod(proxy, method, args)
                }
                when (method.name) {
                    "c" -> {
                        val loggedUin = args?.getOrNull(0) as? String ?: uin
                        onReloginSucceeded(loggedUin)
                    }
                    "b" -> {
                        val err = args?.getOrNull(0)
                        val code = (err?.getObjectOrNull("a") as? Int) ?: -1
                        val msg = (err?.getObjectOrNull("c") as? String).orEmpty()
                        onReloginFailed(activity, code, msg)
                    }
                    "a" -> {
                        onReloginCancelled(activity)
                    }
                }
                null
            }

            val loginMethod = findMainlineLoginMethod(mainline.javaClass)
                ?: error("login method not found on ${mainline.javaClass.name}")
            Log.i("$TAG: invoking ${mainline.javaClass.simpleName}.login for $uin")
            loginMethod.isAccessible = true
            loginMethod.invoke(mainline, activity, loginParams, loadingProxy, callbackProxy)
        }.onFailure {
            Log.e("$TAG: executeMainlineLogin failed", it)
            reloginInProgress.set(false)
            Toasts.error("自动重登发起失败: ${it.message}")
        }
    }

    private fun findMainlineLoginMethod(clazz: Class<*>): Method? {
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            val found = cur.declaredMethods.firstOrNull {
                it.name == "login" && it.parameterTypes.size == 4
            }
            if (found != null) return found
            cur = cur.superclass
        }
        return null
    }

    private fun executeHeadlessNtLogin(uin: String, pwd: String) {
        runCatching {
            val md5Hex = MessageDigest.getInstance("MD5")
                .digest(pwd.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val userInfo = UserInfo().apply {
                account = uin
                accountType = AccountType.ACCOUNTTYPE_UIN
            }
            val appInfo = AppInfo().apply {
                appId = 16L
                appName = "com.tencent.mobileqq"
                qua = BaseApplication.getContext().qua
            }
            val pwdLoginInfo = PwdLoginInfo().apply {
                this.userInfo = userInfo
                this.passwordMd5 = md5Hex
                this.appInfo = appInfo
                this.loginProcessReq = LoginProcessReqBody()
            }

            val loginService = QRoute.api(ILoginService::class.java)
            Log.i("$TAG: invoking headless ILoginService.passwordLogin for $uin")
            loginService.passwordLogin(pwdLoginInfo, object : ILoginCallback {
                override fun onResult(code: Int, msg: String?, result: LoginResult?) {
                    mainHandler.post {
                        val rspCode = result?.rspInfo?.errCode ?: code
                        val rspMsg = result?.rspInfo?.errMsg?.ifEmpty { msg.orEmpty() } ?: msg.orEmpty()
                        if (code == 0 && rspCode == 0 && result != null) {
                            commitHeadlessTicket(uin, result)
                        } else if (rspCode == 140022008 || rspCode == 140022010) {
                            reloginInProgress.set(false)
                            Log.w("$TAG: headless login requires interactive verification (code=$rspCode)")
                            Toasts.info("自动重登需要验证码/设备验证，请打开 QQ 完成验证")
                        } else {
                            onReloginFailed(null, rspCode, rspMsg)
                        }
                    }
                }
            })
        }.onFailure {
            Log.e("$TAG: executeHeadlessNtLogin failed", it)
            reloginInProgress.set(false)
        }
    }

    private fun commitHeadlessTicket(uin: String, result: LoginResult) {
        runCatching {
            val sigInfoClass = loadOrThrow("com.tencent.mobileqq.msf.core.auth.NTAccountSigInfo")
            val sigInfo = sigInfoClass.getConstructor().newInstance()
            sigInfo.callMethod("setUin", result.account.uin)
            sigInfo.callMethod("setUid", result.account.uid)
            sigInfo.callMethod("setUinType", 0)
            sigInfo.callMethod("setA2", result.ticket.a2)
            sigInfo.callMethod("setD2", result.ticket.d2)
            sigInfo.callMethod("setD2key", result.ticket.d2Key)

            val aoClass = loadOrThrow("com.tencent.mobileqq.login.ntlogin.ao")
            val aoInstance = aoClass.getField("a").get(null)!!
            val cbInterface = loadOrThrow("com.tencent.mobileqq.login.ntlogin.a")
            val cbProxy = Proxy.newProxyInstance(
                HookEnv.hostClassLoader,
                arrayOf(cbInterface),
            ) { proxy, method, args ->
                if (method.declaringClass == Any::class.java) {
                    return@newProxyInstance handleObjectProxyMethod(proxy, method, args)
                }
                mainHandler.post {
                    when (method.name) {
                        "onSuccess" -> {
                            runCatching {
                                val msfSdkUtils = load("com.tencent.mobileqq.msf.sdk.MsfSdkUtils")
                                msfSdkUtils?.callStaticMethod("addLoginSimpleAccount", uin, true)
                                val mobileQQ = MobileQQ.getMobileQQ()
                                mobileQQ.callMethod("refreAccountList")
                                val simpleAccountClass = loadOrThrow("com.tencent.qphone.base.remote.SimpleAccount")
                                val sa = simpleAccountClass.getConstructor().newInstance()
                                sa.callMethod("setUin", uin)
                                sa.callMethod("setAttribute", "_isLogined", "true")
                                mobileQQ.callMethod("createNewRuntime", sa, true, true, 3, null)
                            }.onFailure {
                                Log.e("$TAG: createNewRuntime after headless ticket commit failed", it)
                            }
                            onReloginSucceeded(uin)
                        }
                        "onFail" -> {
                            val errCode = (args?.getOrNull(0) as? Int) ?: -1
                            val errMsg = (args?.getOrNull(1) as? String).orEmpty()
                            onReloginFailed(null, errCode, errMsg)
                        }
                    }
                }
                null
            }
            aoInstance.callMethod("d", sigInfo, cbProxy)
        }.onFailure {
            Log.e("$TAG: commitHeadlessTicket failed", it)
            reloginInProgress.set(false)
        }
    }

    private fun handleObjectProxyMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "${proxy.javaClass.name}@${Integer.toHexString(System.identityHashCode(proxy))}"
            else -> null
        }
    }

    private fun onReloginSucceeded(uin: String) {
        reloginInProgress.set(false)
        consecutiveFailures = 0
        suppressedLoginActivityHash = 0
        Log.i("$TAG: auto relogin succeeded for $uin")
        Toasts.success("自动重登成功: $uin")
        SmsCodeBridge.clearChallenge()
    }

    private fun onReloginCancelled(activity: Activity?) {
        reloginInProgress.set(false)
        if (activity != null) {
            suppressedLoginActivityHash = System.identityHashCode(activity)
        }
        Log.i("$TAG: auto relogin cancelled by user")
    }

    private fun onReloginFailed(activity: Activity?, code: Int, msg: String) {
        reloginInProgress.set(false)
        Log.w("$TAG: auto relogin failed code=$code, msg=$msg")
        if (code == 140022010) {
            Toasts.info("需要短信/新设备验证，请完成验证")
            return
        }
        val nonRetryable = code == 140022001 || code == 140022002 || code == 140022003
        if (nonRetryable || consecutiveFailures >= MAX_RETRY_ATTEMPTS) {
            if (activity != null) {
                suppressedLoginActivityHash = System.identityHashCode(activity)
            }
            Toasts.error("自动重登失败 ($code): $msg")
            return
        }
        consecutiveFailures += 1
        val backoffMs = (10_000L shl (consecutiveFailures - 1)).coerceAtMost(60_000L)
        Toasts.info("自动重登失败 ($code)，${backoffMs / 1000} 秒后重试")
        scheduleRelogin(delayMs = backoffMs, reason = "retry#$consecutiveFailures")
    }

    private fun installVerifyWebViewHooks() {
        runCatching {
            val pluginClass = load("com.tencent.mobileqq.vaswebviewplugin.MQPSecJsPlugin")
            val customWebViewClass = load("com.tencent.biz.pubaccount.CustomWebView")
            if (pluginClass != null && customWebViewClass != null) {
                pluginClass.declaredMethods.firstOrNull {
                    it.name == "onWebViewCreated" && it.parameterTypes.size == 1
                }?.hookAfter { param ->
                    val webView = param.args.getOrNull(0) as? WebView ?: return@hookAfter
                    val activity = param.thisObject?.getObjectOrNull("activity") as? Activity
                        ?: findActivity(asView(webView))
                    if (isVerifyWebViewContext(activity, webView.url)) {
                        startVerifyWebViewWatcher(webView, activity)
                    }
                }
            }
        }.onFailure {
            Log.w("$TAG: hook MQPSecJsPlugin skipped: ${it.message}")
        }

        runCatching {
            WebViewClient::class.java.hookMethodAfter(
                "onPageFinished",
                WebView::class.java,
                String::class.java,
            ) { param ->
                val webView = param.args.getOrNull(0) as? WebView ?: return@hookMethodAfter
                val url = param.args.getOrNull(1) as? String ?: webView.url
                if (url.isNullOrBlank() || url == "about:blank") return@hookMethodAfter
                val activity = findActivity(asView(webView))
                if (isVerifyWebViewContext(activity, url)) {
                    startVerifyWebViewWatcher(webView, activity)
                    tickVerifyWebView(webView, activity, url, elapsedMs = 0L)
                }
            }
        }.onFailure {
            Log.w("$TAG: hook WebViewClient.onPageFinished skipped: ${it.message}")
        }
    }

    private fun isVerifyWebViewContext(activity: Activity?, url: String?): Boolean {
        val intent = activity?.intent
        if (intent != null && intent.hasExtra("web_bridge_seq")) return true
        val u = url.orEmpty()
        return u.contains("captcha", ignoreCase = true) ||
            u.contains("turing", ignoreCase = true) ||
            u.contains("aq.qq.com", ignoreCase = true) ||
            u.contains("ti.qq.com", ignoreCase = true) ||
            u.contains("accounts.qq.com", ignoreCase = true)
    }

    private fun isPuzzleCaptchaContext(activity: Activity?, url: String?): Boolean {
        val intent = activity?.intent
        if (intent != null && intent.hasExtra("web_bridge_seq")) {
            val business = intent.getLongExtra("business", 0L)
            if (business == 2_097_152L) return true
        }
        val u = url.orEmpty()
        return u.contains("captcha", ignoreCase = true) ||
            u.contains("turing", ignoreCase = true)
    }

    private fun startVerifyWebViewWatcher(webView: WebView, activity: Activity?) {
        if (!watchedWebViews.add(webView)) return
        val startAt = SystemClock.elapsedRealtime()
        val actRef = WeakReference(activity)
        val wvRef = WeakReference(webView)

        val pollTask = object : Runnable {
            override fun run() {
                val wv = wvRef.get() ?: return
                val act = actRef.get()
                if (act != null && (act.isFinishing || act.isDestroyed)) {
                    watchedWebViews.remove(wv)
                    return
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startAt
                if (elapsedMs > CAPTCHA_WATCH_TIMEOUT_MS) {
                    watchedWebViews.remove(wv)
                    return
                }
                val view = asView(wv)
                if (view != null && view.isAttachedToWindow) {
                    tickVerifyWebView(wv, act, wv.url, elapsedMs)
                }
                mainHandler.postDelayed(this, CAPTCHA_POLL_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(pollTask, CAPTCHA_POLL_INTERVAL_MS)
    }

    private fun tickVerifyWebView(
        webView: WebView,
        activity: Activity?,
        url: String?,
        elapsedMs: Long,
    ) {
        if (options.isFlagEnabled(FLAG_AUTO_CAPTCHA_ACCESSIBILITY) && isPuzzleCaptchaContext(activity, url)) {
            tryClickCaptchaAccessibility(webView, activity, url, elapsedMs)
        }
        if (options.isFlagEnabled(FLAG_AUTO_SMS_VERIFY)) {
            inspectSmsVerifyPage(webView, activity)
        }
    }

    private fun tryClickCaptchaAccessibility(
        webView: WebView,
        activity: Activity?,
        url: String?,
        elapsedMs: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        val lastClick = lastCaptchaClickAt[webView] ?: 0L
        if (now - lastClick < CAPTCHA_CLICK_COOLDOWN_MS) return

        val view = asView(webView) ?: return
        when (tryClickViaAccessibilityTree(view)) {
            A11yTreeResult.CLICKED -> {
                lastCaptchaClickAt[webView] = now
                Log.i("$TAG: clicked dialogSimpleBtn/a11y via AccessibilityNodeProvider (url=$url)")
                Toasts.info("已点击无障碍验证，等待约 25 秒自动通过")
                return
            }
            A11yTreeResult.ALREADY_IN_A11Y -> {
                lastCaptchaClickAt[webView] = now
                return
            }
            A11yTreeResult.NOT_FOUND -> Unit
        }

        webView.evaluateJavascript(CAPTCHA_ACCESSIBILITY_JS) { result ->
            val r = result.orEmpty()
            if (r.contains("already_a11y")) {
                lastCaptchaClickAt[webView] = SystemClock.elapsedRealtime()
                return@evaluateJavascript
            }
            if (r.contains("clicked")) {
                lastCaptchaClickAt[webView] = SystemClock.elapsedRealtime()
                Log.i("$TAG: clicked captcha accessibility button via JS (url=$url)")
                Toasts.info("已点击无障碍验证，等待约 25 秒自动通过")
                return@evaluateJavascript
            }
            val isPuzzleIntent = activity?.intent?.getLongExtra("business", 0L) == 2_097_152L
            if (isPuzzleIntent &&
                elapsedMs >= CAPTCHA_GEOMETRY_DELAY_MS &&
                view.width > 0 &&
                view.height > 0 &&
                SystemClock.elapsedRealtime() - (lastCaptchaClickAt[webView] ?: 0L) >= CAPTCHA_CLICK_COOLDOWN_MS
            ) {
                val density = view.resources.displayMetrics.density
                val tapX = (view.width - 91f * density).coerceIn(1f, view.width - 1f)
                val tapY = (23f * density).coerceIn(1f, view.height - 1f)
                lastCaptchaClickAt[webView] = SystemClock.elapsedRealtime()
                dispatchTap(view, tapX, tapY)
                Log.i("$TAG: tapped tcaptcha_iframe header accessibility fallback ($tapX, $tapY)")
                Toasts.info("已触发无障碍验证，等待约 25 秒自动通过")
            }
        }
    }

    private enum class A11yTreeResult {
        CLICKED,
        ALREADY_IN_A11Y,
        NOT_FOUND,
    }

    private fun asView(webView: WebView): View? = (webView as Any) as? View

    private fun tryClickViaAccessibilityTree(rootView: View): A11yTreeResult {
        val queue = ArrayDeque<View>()
        queue.addLast(rootView)
        val rect = Rect()
        val loc = IntArray(2)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val provider: AccessibilityNodeProvider? = current.accessibilityNodeProvider
            if (provider != null) {
                val nodes = collectAccessibilityNodes(provider)
                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        val text = node.text?.toString().orEmpty()
                        val desc = node.contentDescription?.toString().orEmpty()
                        if (desc.contains("常规挑战") || text.contains("已为你切换到无障碍模式")) {
                            return A11yTreeResult.ALREADY_IN_A11Y
                        }
                    }
                    current.getLocationOnScreen(loc)
                    for (node in nodes) {
                        val text = node.text?.toString().orEmpty()
                        val desc = node.contentDescription?.toString().orEmpty()
                        val viewId = node.viewIdResourceName.orEmpty()
                        val matched = viewId == "dialogSimpleBtn" ||
                            desc.contains("无障碍") ||
                            text.contains("无障碍")
                        if (!matched) continue

                        node.getBoundsInScreen(rect)
                        if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) continue
                        val localX = (rect.centerX() - loc[0]).toFloat()
                        val localY = (rect.centerY() - loc[1]).toFloat()
                        if (localX < 0f || localY < 0f || localX > current.width || localY > current.height) continue

                        dispatchTap(current, localX, localY)
                        return A11yTreeResult.CLICKED
                    }
                }
            }
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(i))
                }
            }
        }
        return A11yTreeResult.NOT_FOUND
    }

    private fun collectAccessibilityNodes(provider: AccessibilityNodeProvider): List<AccessibilityNodeInfo> {
        val collected = ArrayList<AccessibilityNodeInfo>()
        val childMethod = getChildIdMethod
        if (childMethod != null) {
            val visited = HashSet<Int>()
            val idQueue = ArrayDeque<Int>()
            idQueue.addLast(AccessibilityNodeProvider.HOST_VIEW_ID)
            while (idQueue.isNotEmpty() && collected.size < 300) {
                val vid = idQueue.removeFirst()
                if (!visited.add(vid)) continue
                val node = runCatching { provider.createAccessibilityNodeInfo(vid) }.getOrNull() ?: continue
                collected.add(node)
                val count = node.childCount
                for (i in 0 until count) {
                    val childId = runCatching { childMethod.invoke(node, i) as? Long }.getOrNull() ?: continue
                    val childVid = (childId shr 32).toInt()
                    if (childVid !in visited) {
                        idQueue.addLast(childVid)
                    }
                }
            }
        }
        if (collected.isEmpty()) {
            runCatching {
                provider.findAccessibilityNodeInfosByText("无障碍", AccessibilityNodeProvider.HOST_VIEW_ID)
            }.getOrNull()?.let { collected.addAll(it) }
        }
        return collected
    }

    private fun inspectSmsVerifyPage(webView: WebView, activity: Activity?) {
        webView.evaluateJavascript(SMS_PAGE_INSPECT_JS) { raw ->
            val cleaned = unquoteJsResult(raw) ?: return@evaluateJavascript
            val json = runCatching { JSONObject(cleaned) }.getOrNull() ?: return@evaluateJavascript
            val state = json.optString("state", "")
            val phone = json.optString("phone", "")
            val nowElapsed = SystemClock.elapsedRealtime()
            val uin = resolveTargetUin()

            when (state) {
                "send_ready" -> {
                    val lastAction = lastSmsActionAt[webView] ?: 0L
                    if (nowElapsed - lastAction < 30_000L) return@evaluateJavascript
                    lastSmsActionAt[webView] = nowElapsed
                    val nowMs = System.currentTimeMillis()
                    SmsCodeBridge.updateChallenge(hostApp, uin, phone, nowMs, broadcast = true)
                    if (currentProcess == ActionProcess.MAIN) {
                        notifyMilkySmsChallenge(uin, phone, nowMs)
                    }
                    webView.evaluateJavascript(SMS_CLICK_SEND_JS) {
                        Log.i("$TAG: auto-clicked send SMS verification button (phone=$phone)")
                        Toasts.info("已自动发送短信验证码，等待接收...")
                    }
                    startSmsCodePolling(webView, activity, uin, phone, sinceMs = nowMs - 5_000L)
                }
                "input_ready" -> {
                    val challenge = SmsCodeBridge.pendingChallenge
                    val sinceMs = if (challenge != null && (System.currentTimeMillis() - challenge.timestampMs) < 180_000L) {
                        challenge.timestampMs - 5_000L
                    } else {
                        val nowMs = System.currentTimeMillis()
                        SmsCodeBridge.updateChallenge(hostApp, uin, phone, nowMs, broadcast = true)
                        if (currentProcess == ActionProcess.MAIN) {
                            notifyMilkySmsChallenge(uin, phone, nowMs)
                        }
                        nowMs - 120_000L
                    }
                    startSmsCodePolling(webView, activity, uin, phone, sinceMs = sinceMs)
                }
            }
        }
    }

    private fun startSmsCodePolling(
        webView: WebView,
        activity: Activity?,
        uin: String,
        maskedPhone: String,
        sinceMs: Long,
    ) {
        if (!smsPollingActive.add(webView)) return
        val wvRef = WeakReference(webView)
        val actRef = WeakReference(activity)
        val fetchUrl = smsFetchUrl
        val codeRegex = smsCodeRegex
        val startElapsed = SystemClock.elapsedRealtime()

        ModuleScope.launchIO("$TAG-SmsPoll") {
            try {
                while (SystemClock.elapsedRealtime() - startElapsed < SMS_POLL_TIMEOUT_MS) {
                    val act = actRef.get()
                    val wv = wvRef.get() ?: break
                    if (act != null && (act.isFinishing || act.isDestroyed)) break

                    val code = SmsCodeBridge.consumeSubmittedCode(sinceMs)
                        ?: SmsCodeBridge.queryLocalProvider(hostApp, sinceMs)
                        ?: SmsCodeBridge.fetchFromHttpUrl(fetchUrl, codeRegex, uin, maskedPhone)

                    if (!code.isNullOrBlank()) {
                        Log.i("$TAG: obtained SMS code (len=${code.length}), filling into WebView")
                        ModuleScope.onMain {
                            fillSmsCodeIntoWebView(wv, code)
                        }
                        break
                    }
                    delay(SMS_POLL_INTERVAL_MS)
                }
            } finally {
                smsPollingActive.remove(webView)
            }
        }
    }

    private fun fillSmsCodeIntoWebView(webView: WebView, code: String) {
        Toasts.info("已获取验证码，正在自动填写")
        webView.evaluateJavascript(buildFillSmsJs(code)) {
            mainHandler.postDelayed({
                webView.evaluateJavascript(SMS_PAGE_INSPECT_JS) { raw ->
                    val cleaned = unquoteJsResult(raw).orEmpty()
                    if (cleaned.contains("input_ready")) {
                        val view = asView(webView) ?: return@evaluateJavascript
                        webView.evaluateJavascript(SMS_FOCUS_INPUT_JS) {
                            dispatchDigitKeys(view, code)
                        }
                    }
                }
            }, 1_200L)
        }
    }

    private fun dispatchDigitKeys(target: View, code: String) {
        target.requestFocus()
        var delayMs = 80L
        for (ch in code) {
            if (ch !in '0'..'9') continue
            val keyCode = KeyEvent.KEYCODE_0 + (ch - '0')
            mainHandler.postDelayed({
                val now = SystemClock.uptimeMillis()
                target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                target.dispatchKeyEvent(KeyEvent(now, now + 20L, KeyEvent.ACTION_UP, keyCode, 0))
            }, delayMs)
            delayMs += 60L
        }
    }

    private fun unquoteJsResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrNull()
        }
        return raw
    }

    private fun dispatchTap(target: View, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        target.dispatchTouchEvent(down)
        down.recycle()

        mainHandler.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
            target.dispatchTouchEvent(up)
            up.recycle()
        }, 45L)
    }

    private fun findLifecycleMethod(clazz: Class<*>, vararg names: String): Method? {
        for (methodName in names) {
            var cur: Class<*>? = clazz
            while (cur != null && cur != Any::class.java) {
                val m = cur.declaredMethods.firstOrNull { it.name == methodName }
                if (m != null) return m
                cur = cur.superclass
            }
        }
        return null
    }

    private fun findActivity(view: View?): Activity? {
        var ctx: Context? = view?.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    private const val CAPTCHA_ACCESSIBILITY_JS = """
        (function() {
            var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
            if (bodyText.indexOf('已为你切换到无障碍模式') !== -1) {
                return 'already_a11y';
            }
            var now = Date.now();
            if (window.__tcqtA11yClickedAt && (now - window.__tcqtA11yClickedAt) < 35000) {
                return 'cooldown';
            }
            function scanDoc(doc) {
                if (!doc) return false;
                var direct = doc.getElementById('dialogSimpleBtn');
                if (direct) {
                    var directLabel = (direct.getAttribute('aria-label') || direct.innerText || '').trim();
                    if (directLabel.indexOf('常规挑战') !== -1) return 'already_a11y';
                    window.__tcqtA11yClickedAt = now;
                    direct.click();
                    return 'clicked';
                }
                var candidates = doc.querySelectorAll('button, a, [role="button"], div, span');
                for (var i = 0; i < candidates.length; i++) {
                    var el = candidates[i];
                    var label = ((el.innerText || el.textContent || '') + ' ' +
                        (el.getAttribute('aria-label') || '') + ' ' +
                        (el.getAttribute('title') || '')).trim();
                    if (!label || label.indexOf('无障碍') === -1 || label.length > 40) continue;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) continue;
                    window.__tcqtA11yClickedAt = now;
                    el.click();
                    return 'clicked';
                }
                var frames = doc.querySelectorAll('iframe');
                for (var j = 0; j < frames.length; j++) {
                    try {
                        var res = scanDoc(frames[j].contentDocument);
                        if (res) return res;
                    } catch (e) {}
                }
                return false;
            }
            return scanDoc(document) || 'not_found';
        })();
    """

    private const val SMS_PAGE_INSPECT_JS = """
        (function() {
            var text = (document.body && document.body.innerText) ? document.body.innerText : '';
            var phoneMatch = text.match(/1\d{2}\*{3,}\d{2}/);
            var phone = phoneMatch ? phoneMatch[0] : '';
            if (text.indexOf('输入短信验证码') !== -1) {
                return JSON.stringify({ state: 'input_ready', phone: phone });
            }
            var btns = document.querySelectorAll('button');
            for (var i = 0; i < btns.length; i++) {
                if ((btns[i].innerText || '').indexOf('发送短信验证码') !== -1) {
                    return JSON.stringify({ state: 'send_ready', phone: phone });
                }
            }
            return JSON.stringify({ state: 'none', phone: phone });
        })();
    """

    private const val SMS_CLICK_SEND_JS = """
        (function() {
            var btns = document.querySelectorAll('button');
            for (var i = 0; i < btns.length; i++) {
                if ((btns[i].innerText || '').indexOf('发送短信验证码') !== -1) {
                    btns[i].click();
                    return true;
                }
            }
            return false;
        })();
    """

    private const val SMS_FOCUS_INPUT_JS = """
        (function() {
            var input = document.querySelector('input');
            if (input) {
                input.focus();
                input.click();
                return true;
            }
            return false;
        })();
    """

    private fun buildFillSmsJs(code: String): String {
        val safe = JSONObject.quote(code)
        return """
            (function() {
                var code = $safe;
                var input = document.querySelector('input');
                if (!input) return false;
                input.focus();
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (setter && setter.set) {
                    setter.set.call(input, code);
                } else {
                    input.value = code;
                }
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                return true;
            })();
        """.trimIndent()
    }
}
