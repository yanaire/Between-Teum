package dev.pleos.starter.teum.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import com.google.gson.JsonObject

/** Permission grants are read from Android every time, never accepted from HTML. */
class TeumWebPermissions(
    private val activity: Activity,
    private val onChanged: () -> Unit
) {
    private var pending: WebPermission? = null
    private var explanation: AlertDialog? = null

    fun snapshot(): JsonObject = JsonObject().apply {
        addProperty("microphone", granted(Manifest.permission.RECORD_AUDIO))
        addProperty("onDeviceSpeech", granted("pleos.car.permission.STT_SERVICE"))
        addProperty("location", if (granted("pleos.car.permission.FUSED_LOCATION")) "granted" else "denied")
        addProperty("requestPending", pending != null)
    }

    fun request(permission: WebPermission) {
        if (pending != null || explanation != null) return
        val permissions = when (permission) {
            WebPermission.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO, "pleos.car.permission.STT_SERVICE")
            WebPermission.LOCATION -> arrayOf("pleos.car.permission.FUSED_LOCATION")
        }
        if (permissions.all(::granted)) {
            onChanged()
            return
        }
        if (permission == WebPermission.LOCATION || permissions.any(activity::shouldShowRequestPermissionRationale)) {
            explanation = AlertDialog.Builder(activity)
                .setTitle(if (permission == WebPermission.MICROPHONE) "말로 기록하기" else "도착 장소 등록")
                .setMessage(if (permission == WebPermission.MICROPHONE)
                    "다짐과 이야기를 말로 남기려면 마이크 권한이 필요해요. 허용하지 않아도 다른 기능을 이용할 수 있어요."
                    else "현재 위치를 집이나 회사로 등록할 때 위치 권한이 필요해요. 허용하지 않으면 장소를 직접 입력할 수 있어요.")
                .setNegativeButton("나중에") { _, _ -> onChanged() }
                .setPositiveButton("권한 요청") { _, _ -> launch(permission, permissions) }
                .setOnDismissListener { explanation = null }
                .show()
        } else launch(permission, permissions)
    }

    private fun launch(permission: WebPermission, permissions: Array<String>) {
        pending = permission
        activity.requestPermissions(permissions, REQUEST_CODE)
        onChanged()
    }

    fun onResult(requestCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        pending = null
        onChanged()
        return true
    }

    fun close() {
        explanation?.dismiss()
        explanation = null
        pending = null
    }

    private fun granted(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQUEST_CODE = 7041
    }
}
